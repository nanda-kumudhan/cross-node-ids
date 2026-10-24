/* Minimal named CO-RE views; field offsets are relocated using target BTF.
 * These are not native kernel layouts. x86-64 pt_regs comes from asm/ptrace.h.
 */
#ifndef IDS_KERNEL_TYPES_H
#define IDS_KERNEL_TYPES_H
#include <linux/types.h>
#define CORE_TYPE __attribute__((preserve_access_index))
struct task_struct {
    __u64 start_time;
    struct task_struct *group_leader;
} CORE_TYPE;
struct sock_common {
    __u32 skc_daddr;
    __u32 skc_rcv_saddr;
    __u16 skc_dport;
    __u16 skc_num;
    __u16 skc_family;
} CORE_TYPE;
struct sock {
    struct sock_common __sk_common;
    unsigned int sk_protocol:8;
} CORE_TYPE;
struct trace_event_raw_sched_process_exec {
    __u64 unused;
    __u32 __data_loc_filename;
} CORE_TYPE;
struct trace_event_raw_sys_exit {
    __u64 unused;
    long id;
    long ret;
} CORE_TYPE;
#endif
