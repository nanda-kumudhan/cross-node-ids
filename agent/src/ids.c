// SPDX-License-Identifier: BSD-2-Clause
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <inttypes.h>
#include <limits.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <bpf/bpf.h>
#include <bpf/libbpf.h>
#include "events.h"

#define MAX_SEQUENCE UINT64_C(9007199254740991)
static volatile sig_atomic_t stopping;
struct agent {
    FILE *output;
    const char *node;
    char boot[64], run[64];
    uint64_t sequence;
    int64_t clock_offset;
    uint64_t clock_uncertainty;
    struct ids_counters counts;
    bool synthetic;
    bool ready;
};
static void stop(int signal_number) { (void)signal_number; stopping = 1; }
static uint64_t nanoseconds(const struct timespec *value) { return (uint64_t)value->tv_sec * 1000000000 + value->tv_nsec; }
static int sample_clock(struct agent *agent)
{
    struct timespec before, wall, after;
    if (clock_gettime(CLOCK_MONOTONIC, &before) || clock_gettime(CLOCK_REALTIME, &wall) || clock_gettime(CLOCK_MONOTONIC, &after)) return -errno;
    uint64_t first = nanoseconds(&before), last = nanoseconds(&after);
    agent->clock_offset = (int64_t)nanoseconds(&wall) - (int64_t)(first + (last - first) / 2);
    agent->clock_uncertainty = (last - first + 1) / 2;
    return 0;
}
static int read_identity(const char *path, char *value, size_t size)
{
    FILE *file = fopen(path, "r");
    if (!file) return -errno;
    bool ok = fgets(value, (int)size, file) != NULL;
    fclose(file);
    if (!ok) return -EIO;
    value[strcspn(value, "\r\n")] = 0;
    return value[0] ? 0 : -EINVAL;
}
/* Byte escaping keeps JSON valid even for non-UTF8 Linux path bytes. */
static void json_string(FILE *file, const char *value)
{
    fputc('"', file);
    for (const unsigned char *byte = (const unsigned char *)value; *byte; byte++) {
        if (*byte == '"' || *byte == '\\') fprintf(file, "\\%c", *byte);
        else if (*byte < 32 || *byte >= 127) fprintf(file, "\\u%04x", *byte);
        else fputc(*byte, file);
    }
    fputc('"', file);
}
static int emit(struct agent *agent, const struct ids_event *event, bool final)
{
    if (agent->sequence == MAX_SEQUENCE) return -EOVERFLOW;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return -errno;
    uint64_t mono = event ? event->monotonic_ns : nanoseconds(&now);
    int64_t wall = (int64_t)mono + agent->clock_offset;
    if (wall < 0) return -ERANGE;
    time_t seconds = wall / 1000000000;
    struct tm utc;
    char timestamp[32];
    if (!gmtime_r(&seconds, &utc) || !strftime(timestamp, sizeof(timestamp), "%Y-%m-%dT%H:%M:%S", &utc)) return -ERANGE;
    char *line = NULL;
    size_t length = 0;
    FILE *memory = open_memstream(&line, &length);
    if (!memory) return -errno;
    uint64_t sequence = ++agent->sequence;
    const char *type = !event ? "heartbeat" : event->kind == IDS_EXEC ? "exec" : event->kind == IDS_CONNECT ? "connect" : "accept";
    fprintf(memory, "{\"schema_version\":1,\"node\":"); json_string(memory, agent->node);
    fprintf(memory, ",\"boot_id\":"); json_string(memory, agent->boot);
    fprintf(memory, ",\"agent_run_id\":"); json_string(memory, agent->run);
    fprintf(memory, ",\"seq\":%" PRIu64 ",\"timestamp\":\"%s.%09" PRId64 "Z\",\"type\":\"%s\",\"pid\":%u,\"process_start_ns\":%" PRIu64,
        sequence, timestamp, wall % 1000000000, type, event ? event->pid : 0, event ? (uint64_t)event->process_start_ns : 0);
    fprintf(memory, ",\"monotonic_ns\":%" PRIu64 ",\"clock_uncertainty_ns\":%" PRIu64 ",\"kernel_drops\":%" PRIu64 ",\"capture_errors\":%" PRIu64,
        mono, agent->clock_uncertainty, (uint64_t)agent->counts.kernel_drops, (uint64_t)agent->counts.capture_errors);
    if (agent->synthetic) fprintf(memory, ",\"synthetic\":true");
    if (!event) fprintf(memory, ",\"high_watermark\":%" PRIu64 ",\"final\":%s,\"ready\":%s", sequence, final ? "true" : "false", agent->ready ? "true" : "false");
    else if (event->kind == IDS_EXEC) {
        fprintf(memory, ",\"executable\":"); json_string(memory, event->executable);
        fprintf(memory, ",\"path_truncated\":%s,\"argument_policy\":\"omitted\"", event->path_truncated ? "true" : "false");
    } else {
        char source[INET_ADDRSTRLEN], destination[INET_ADDRSTRLEN];
        if (!inet_ntop(AF_INET, &event->source_ip, source, sizeof(source)) || !inet_ntop(AF_INET, &event->destination_ip, destination, sizeof(destination))) {
            fclose(memory); free(line); return -errno;
        }
        fprintf(memory, ",\"protocol\":\"tcp\",\"address_family\":\"ipv4\",\"success\":true,\"src_ip\":\"%s\",\"src_port\":%u,\"dst_ip\":\"%s\",\"dst_port\":%u",
            source, event->source_port, destination, event->destination_port);
    }
    if (event) fprintf(memory, ",\"userspace_delay_ns\":%" PRIu64, nanoseconds(&now) >= mono ? nanoseconds(&now) - mono : 0);
    fprintf(memory, "}\n");
    int error = ferror(memory) ? -EIO : 0;
    if (fclose(memory) && !error) error = -EIO;
    if (!error && (fwrite(line, 1, length, agent->output) != length || fflush(agent->output))) error = -EIO;
    free(line);
    return error;
}
static int handle_event(void *context, void *data, size_t size)
{
    if (size != sizeof(struct ids_event)) return -EBADMSG;
    struct ids_event event;
    memcpy(&event, data, sizeof(event));
    if (event.kind < IDS_EXEC || event.kind > IDS_ACCEPT) return -EBADMSG;
    event.executable[sizeof(event.executable) - 1] = 0;
    return emit(context, &event, false);
}
static int read_counters(struct agent *agent, struct bpf_object *object)
{
    int cpus = libbpf_num_possible_cpus();
    if (cpus < 0) return cpus;
    struct ids_counters *values = calloc((size_t)cpus, sizeof(*values));
    if (!values) return -ENOMEM;
    __u32 key = 0;
    int fd = bpf_object__find_map_fd_by_name(object, "counters");
    int error = fd < 0 ? fd : bpf_map_lookup_elem(fd, &key, values) ? -errno : 0;
    if (!error) {
        memset(&agent->counts, 0, sizeof(agent->counts));
        for (int cpu = 0; cpu < cpus; cpu++) {
            agent->counts.kernel_drops += values[cpu].kernel_drops;
            agent->counts.capture_errors += values[cpu].capture_errors;
        }
    }
    free(values);
    return error;
}
static int self_test(struct agent *agent)
{
    agent->synthetic = true;
    int error = emit(agent, NULL, false);
    if (error) return error;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return -errno;
    struct ids_event event = { .monotonic_ns = nanoseconds(&now), .process_start_ns = 1, .pid = (unsigned)getpid(), .kind = IDS_EXEC };
    snprintf(event.executable, sizeof(event.executable), "/tmp/quoted-\"name\\control\n");
    if ((error = emit(agent, &event, false))) return error;
    inet_pton(AF_INET, "10.0.0.1", &event.source_ip); inet_pton(AF_INET, "10.0.0.2", &event.destination_ip);
    event.source_port = 40000; event.destination_port = 2222;
    event.kind = IDS_CONNECT;
    if ((error = emit(agent, &event, false))) return error;
    event.kind = IDS_ACCEPT;
    if ((error = emit(agent, &event, false))) return error;
    return emit(agent, NULL, true);
}
static long positive_number(const char *text, long maximum)
{
    char *end;
    errno = 0;
    long number = strtol(text, &end, 10);
    return errno || *end || end == text || number < 1 || number > maximum ? -1 : number;
}
int main(int argc, char **argv)
{
    const char *node = getenv("NODE_ID"), *output = getenv("OUTPUT_PATH"), *object_path = "agent/bin/ids.bpf.o";
    bool test = false;
    long duration = 0, ring_bytes = 1024 * 1024;
    static const struct option options[] = {
        {"node", required_argument, NULL, 'n'}, {"output", required_argument, NULL, 'o'},
        {"object", required_argument, NULL, 'b'}, {"duration", required_argument, NULL, 'd'},
        {"ring-bytes", required_argument, NULL, 'r'}, {"self-test", no_argument, NULL, 't'}, {NULL, 0, NULL, 0}
    };
    int choice;
    while ((choice = getopt_long(argc, argv, "n:o:b:d:r:t", options, NULL)) != -1) {
        switch (choice) {
            case 'n': node = optarg; break; case 'o': output = optarg; break; case 'b': object_path = optarg; break;
            case 't': test = true; break;
            case 'd': duration = positive_number(optarg, 86400); if (duration < 0) return 2; break;
            case 'r': ring_bytes = positive_number(optarg, 64 * 1024 * 1024); if (ring_bytes < 4096 || (ring_bytes & (ring_bytes - 1))) return 2; break;
            default: return 2;
        }
    }
    if (optind != argc || !node || !node[0] || strlen(node) > 128) {
        fprintf(stderr, "Usage: ids-agent --node ID [--output FILE] [--object FILE] [--duration SECONDS] [--self-test]\n"); return 2;
    }
    struct agent agent = { .node = node, .output = stdout };
    int error = read_identity("/proc/sys/kernel/random/boot_id", agent.boot, sizeof(agent.boot));
    if (!error) error = read_identity("/proc/sys/kernel/random/uuid", agent.run, sizeof(agent.run));
    if (!error) error = sample_clock(&agent);
    if (error) { fprintf(stderr, "Clock/identity initialization failed: %s\n", strerror(-error)); return 1; }
    if (output && strcmp(output, "-")) {
        int fd = open(output, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0640);
        if (fd < 0) { perror("open output"); return 1; }
        agent.output = fdopen(fd, "a");
        if (!agent.output) { perror("fdopen"); close(fd); return 1; }
    }
    if (test) {
        error = self_test(&agent);
        if (agent.output != stdout && fclose(agent.output)) error = -EIO;
        return error ? 1 : 0;
    }
    struct sigaction action = { .sa_handler = stop };
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, NULL) || sigaction(SIGTERM, &action, NULL)) { perror("sigaction"); return 1; }
    struct bpf_object *object = bpf_object__open_file(object_path, NULL);
    struct bpf_link *links[16] = {0};
    size_t link_count = 0;
    struct ring_buffer *reader = NULL;
    if (!object) { error = -errno; goto cleanup; }
    struct bpf_map *ring = bpf_object__find_map_by_name(object, "events");
    if (!ring) { error = -ENOENT; goto cleanup; }
    if ((error = bpf_map__set_max_entries(ring, (unsigned)ring_bytes)) || (error = bpf_object__load(object))) goto cleanup;
    reader = ring_buffer__new(bpf_map__fd(ring), handle_event, &agent, NULL);
    if (!reader) { error = -errno; goto cleanup; }
    if ((error = emit(&agent, NULL, false))) goto cleanup;
    struct bpf_program *program;
    bpf_object__for_each_program(program, object) {
        if (link_count == sizeof(links) / sizeof(*links)) { error = -E2BIG; goto cleanup; }
        struct bpf_link *link = bpf_program__attach(program);
        if (!link) { error = -errno; goto cleanup; }
        links[link_count++] = link;
    }
    agent.ready = true;
    if ((error = emit(&agent, NULL, false))) goto cleanup;
    struct timespec started, current;
    if (clock_gettime(CLOCK_MONOTONIC, &started)) { error = -errno; goto cleanup; }
    uint64_t last_heartbeat = nanoseconds(&started);
    while (!stopping) {
        error = ring_buffer__poll(reader, 100);
        if (error == -EINTR) error = 0;
        if (error < 0) goto cleanup;
        error = 0;
        if (clock_gettime(CLOCK_MONOTONIC, &current)) { error = -errno; goto cleanup; }
        if (nanoseconds(&current) - last_heartbeat >= 1000000000) {
            if ((error = sample_clock(&agent)) || (error = read_counters(&agent, object)) || (error = emit(&agent, NULL, false))) goto cleanup;
            last_heartbeat = nanoseconds(&current);
        }
        if (duration && nanoseconds(&current) - nanoseconds(&started) >= (uint64_t)duration * 1000000000) break;
    }
    while (link_count) bpf_link__destroy(links[--link_count]);
    agent.ready = false;
    do { error = ring_buffer__consume(reader); } while (error > 0);
    if (!error && !(error = read_counters(&agent, object)) && !(error = sample_clock(&agent))) error = emit(&agent, NULL, true);
cleanup:
    if (error < 0) fprintf(stderr, "Agent failed: %s (check BTF, kernel hooks and BPF/perf permissions)\n", strerror(-error));
    while (link_count) bpf_link__destroy(links[--link_count]);
    ring_buffer__free(reader);
    bpf_object__close(object);
    if (agent.output != stdout && fclose(agent.output)) error = -EIO;
    return error < 0 ? 1 : 0;
}
