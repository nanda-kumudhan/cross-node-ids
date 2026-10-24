// SPDX-License-Identifier: GPL-2.0-only
#include <linux/bpf.h>
#include <asm/ptrace.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_core_read.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_endian.h>
#include "kernel_types.h"
#include "events.h"

char ids_license[] SEC("license") = "GPL";
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 1024 * 1024);
} events SEC(".maps");
struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(max_entries, 1);
    __type(key, __u32);
    __type(value, struct ids_counters);
} counters SEC(".maps");
struct pending_socket { struct sock *socket; __u32 kind; };
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 8192);
    __type(key, __u64);
    __type(value, struct pending_socket);
} pending SEC(".maps");

static __always_inline void count_failure(int ring_full)
{
    __u32 zero = 0;
    struct ids_counters *value = bpf_map_lookup_elem(&counters, &zero);
    if (!value) return;
    if (ring_full) __sync_fetch_and_add(&value->kernel_drops, 1);
    else __sync_fetch_and_add(&value->capture_errors, 1);
}

static __always_inline struct ids_event *allocate(__u32 kind)
{
    struct ids_event *event = bpf_ringbuf_reserve(&events, sizeof(*event), 0);
    if (!event) { count_failure(1); return 0; }
    __builtin_memset(event, 0, sizeof(*event));
    event->kind = kind;
    event->pid = bpf_get_current_pid_tgid() >> 32;
    event->monotonic_ns = bpf_ktime_get_ns();
    struct task_struct *task = (void *)bpf_get_current_task();
    if (BPF_CORE_READ_INTO(&event->process_start_ns, task, group_leader, start_time)) {
        count_failure(0); bpf_ringbuf_discard(event, 0); return 0;
    }
    return event;
}

SEC("tp/sched/sched_process_exec")
int process_exec(struct trace_event_raw_sched_process_exec *context)
{
    struct ids_event *event = allocate(IDS_EXEC);
    if (!event) return 0;
    __u32 offset = BPF_CORE_READ(context, __data_loc_filename) & 0xffff;
    long length = bpf_probe_read_kernel_str(event->executable, sizeof(event->executable), (char *)context + offset);
    if (length <= 0) { count_failure(0); bpf_ringbuf_discard(event, 0); return 0; }
    event->path_truncated = length == sizeof(event->executable);
    bpf_ringbuf_submit(event, 0);
    return 0;
}

static __always_inline void remember(struct sock *socket, __u32 kind)
{
    __u64 thread = bpf_get_current_pid_tgid();
    struct pending_socket value = { .socket = socket, .kind = kind };
    if (bpf_map_lookup_elem(&pending, &thread)) count_failure(0);
    if (socket && (unsigned long)socket < (unsigned long)-4095 && bpf_map_update_elem(&pending, &thread, &value, BPF_ANY))
        count_failure(0);
}

/* tcp_v4_connect return=0 means SYN initiation, NOT completed connect.
 * Save the socket here and emit only after the userspace syscall succeeds.
 */
SEC("kprobe/tcp_v4_connect")
int BPF_KPROBE(connect_socket, struct sock *socket)
{
    remember(socket, IDS_CONNECT);
    return 0;
}

SEC("kretprobe/inet_csk_accept")
int BPF_KRETPROBE(accepted_socket, struct sock *socket)
{
    remember(socket, IDS_ACCEPT);
    return 0;
}

static __always_inline int completed(struct trace_event_raw_sys_exit *context, __u32 kind)
{
    __u64 thread = bpf_get_current_pid_tgid();
    struct pending_socket *saved = bpf_map_lookup_elem(&pending, &thread);
    if (!saved) return 0;
    struct sock *socket = saved->socket;
    __u32 actual_kind = saved->kind;
    bpf_map_delete_elem(&pending, &thread);
    long result = BPF_CORE_READ(context, ret);
    if (actual_kind != kind || (kind == IDS_CONNECT ? result != 0 : result < 0)) return 0;
    __u16 family = 0;
    if (BPF_CORE_READ_INTO(&family, socket, __sk_common.skc_family)) { count_failure(0); return 0; }
    if (family != 2) return 0;
    __u64 protocol = BPF_CORE_READ_BITFIELD_PROBED(socket, sk_protocol);
    if (protocol != 6) { if (!protocol) count_failure(0); return 0; }
    struct ids_event *event = allocate(kind);
    if (!event) return 0;
    __u32 local_ip = 0, remote_ip = 0;
    __u16 local_port = 0, remote_port = 0;
    int failed = BPF_CORE_READ_INTO(&local_ip, socket, __sk_common.skc_rcv_saddr);
    failed |= BPF_CORE_READ_INTO(&remote_ip, socket, __sk_common.skc_daddr);
    failed |= BPF_CORE_READ_INTO(&local_port, socket, __sk_common.skc_num);
    failed |= BPF_CORE_READ_INTO(&remote_port, socket, __sk_common.skc_dport);
    if (failed || !local_port || !remote_port) { count_failure(0); bpf_ringbuf_discard(event, 0); return 0; }
    event->source_ip = kind == IDS_CONNECT ? local_ip : remote_ip;
    event->destination_ip = kind == IDS_CONNECT ? remote_ip : local_ip;
    event->source_port = kind == IDS_CONNECT ? local_port : bpf_ntohs(remote_port);
    event->destination_port = kind == IDS_CONNECT ? bpf_ntohs(remote_port) : local_port;
    bpf_ringbuf_submit(event, 0);
    return 0;
}
SEC("tp/syscalls/sys_exit_connect")
int exit_connect(struct trace_event_raw_sys_exit *context) { return completed(context, IDS_CONNECT); }
SEC("tp/syscalls/sys_exit_accept")
int exit_accept(struct trace_event_raw_sys_exit *context) { return completed(context, IDS_ACCEPT); }
SEC("tp/syscalls/sys_exit_accept4")
int exit_accept4(struct trace_event_raw_sys_exit *context) { return completed(context, IDS_ACCEPT); }
SEC("tp/sched/sched_process_exit")
int process_exit(void *context)
{
    __u64 thread = bpf_get_current_pid_tgid();
    bpf_map_delete_elem(&pending, &thread);
    return 0;
}
