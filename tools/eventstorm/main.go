package main

import (
	"bufio"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"syscall"
	"time"
)

type event struct {
	SchemaVersion int    `json:"schema_version"`
	Node          string `json:"node"`
	BootID        string `json:"boot_id"`
	AgentRunID    string `json:"agent_run_id"`
	Seq           uint64 `json:"seq"`
	Timestamp     string `json:"timestamp"`
	Type          string `json:"type"`
	PID           int    `json:"pid"`
	ProcessStart  uint64 `json:"process_start_ns"`
	Executable    string `json:"executable,omitempty"`
	Synthetic     bool   `json:"synthetic"`
}

func main() {
	node := flag.String("node", "synthetic-node", "node identity")
	boot := flag.String("boot-id", "synthetic-boot", "kernel boot identity (synthetic in json mode)")
	run := flag.String("run-id", fmt.Sprintf("synthetic-%d", time.Now().UnixNano()), "unique producer run identity")
	count := flag.Uint64("count", 10000, "maximum event or workload count")
	rate := flag.Int("rate", 1000, "operations per second; 0 means unpaced")
	start := flag.Uint64("start-seq", 1, "first sequence number")
	mode := flag.String("mode", "json", "json, exec, or connect")
	target := flag.String("target", "", "connect target host:port; loopback only")
	flag.Parse()
	if *rate < 0 || *rate > 1000000 || *node == "" || *boot == "" || *run == "" || *count == 0 || *start == 0 {
		fatal(fmt.Errorf("require nonempty identities, positive count/sequence, rate 0..1000000"))
	}
	const maxSequence = uint64(1<<63 - 1)
	if *start > maxSequence || *count-1 > maxSequence-*start {
		fatal(fmt.Errorf("sequence range overflows the signed 64-bit event contract"))
	}
	if *mode != "json" && *mode != "exec" && *mode != "connect" {
		fatal(fmt.Errorf("unknown mode %q", *mode))
	}
	if *mode == "connect" {
		host, _, err := net.SplitHostPort(*target)
		if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
			fatal(fmt.Errorf("connect target must be an explicit loopback IP:port"))
		}
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	err := generate(ctx, os.Stdout, *mode, *node, *boot, *run, *target, *count, *start, *rate)
	if err != nil {
		fatal(err)
	}
}

func generate(ctx context.Context, out io.Writer, mode, node, boot, run, target string, count, first uint64, rate int) error {
	writer := bufio.NewWriterSize(out, 64*1024)
	encoder := json.NewEncoder(writer)
	started := time.Now()
	var completed uint64
	var ticker *time.Ticker
	if rate > 0 {
		ticker = time.NewTicker(time.Second / time.Duration(rate))
		defer ticker.Stop()
	}
	for i := uint64(0); i < count; i++ {
		if ticker != nil {
			select {
			case <-ticker.C:
			case <-ctx.Done():
				return writer.Flush()
			}
		} else if ctx.Err() != nil {
			return writer.Flush()
		}
		var err error
		switch mode {
		case "json":
			err = encoder.Encode(event{1, node, boot, run, first + i, time.Now().UTC().Format(time.RFC3339Nano),
				"exec", os.Getpid(), 0, "/usr/bin/true", true})
		case "exec":
			err = exec.CommandContext(ctx, "/usr/bin/true").Run()
		case "connect":
			dialer := net.Dialer{Timeout: time.Second}
			var conn net.Conn
			conn, err = dialer.DialContext(ctx, "tcp", target)
			if err == nil {
				err = conn.Close()
			}
		}
		if err != nil {
			return fmt.Errorf("operation %d: %w", i, err)
		}
		completed++
	}
	if err := writer.Flush(); err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "mode=%s completed=%d elapsed=%s requested_rate=%d/s\n", mode, completed, time.Since(started), rate)
	return nil
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
