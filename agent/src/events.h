#ifndef IDS_EVENTS_H
#define IDS_EVENTS_H
#include <linux/types.h>

enum ids_kind { IDS_EXEC = 1, IDS_CONNECT = 2, IDS_ACCEPT = 3 };
struct ids_event {
    __u64 monotonic_ns;
    __u64 process_start_ns;
    __u32 pid;
    __u32 kind;
    __u32 source_ip;
    __u32 destination_ip;
    __u16 source_port;
    __u16 destination_port;
    __u32 path_truncated;
    char executable[256];
};
struct ids_counters { __u64 kernel_drops; __u64 capture_errors; };
#endif
