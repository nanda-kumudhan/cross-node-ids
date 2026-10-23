package main

import (
	"bytes"
	"context"
	"encoding/json"
	"testing"
)

func TestJSONSequenceAndRedaction(t *testing.T) {
	var output bytes.Buffer
	if err := generate(context.Background(), &output, "json", "a", "boot-a", "run-a", "", 3, 41, 0); err != nil {
		t.Fatal(err)
	}
	decoder := json.NewDecoder(&output)
	for seq := uint64(41); seq < 44; seq++ {
		var got map[string]any
		if err := decoder.Decode(&got); err != nil {
			t.Fatal(err)
		}
		if got["seq"] != float64(seq) || got["node"] != "a" || got["synthetic"] != true {
			t.Fatalf("unexpected event: %v", got)
		}
		if _, exists := got["args"]; exists {
			t.Fatal("arguments should not be captured")
		}
	}
	if decoder.More() {
		t.Fatal("unexpected extra event")
	}
}

func TestCancelledGeneration(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var output bytes.Buffer
	if err := generate(ctx, &output, "json", "a", "b", "run-b", "", 10, 1, 0); err != nil || output.Len() != 0 {
		t.Fatalf("cancellation failed: %v", err)
	}
}
