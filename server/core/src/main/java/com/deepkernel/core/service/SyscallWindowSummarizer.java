package com.deepkernel.core.service;

import com.deepkernel.contracts.model.TraceRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Produces a compact human-readable summary of a 5s syscall window for LLM triage.
 * Keeps output small and deterministic for demo use.
 */
public class SyscallWindowSummarizer {

    public String summarize(List<TraceRecord> records) {
        if (records == null || records.isEmpty()) {
            return "(no records)";
        }

        Map<Integer, Integer> counts = new HashMap<>();
        int fileOps = 0, netOps = 0, procOps = 0, memOps = 0, otherOps = 0;

        for (TraceRecord r : records) {
            counts.merge(r.syscall_id(), 1, Integer::sum);
            switch (r.arg_class()) {
                case 1 -> fileOps++;
                case 2 -> netOps++;
                case 3 -> procOps++;
                case 4 -> memOps++;
                default -> otherOps++;
            }
        }

        String topSyscalls = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(e -> mapSyscallName(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(", "));

        // Very small sequence sample (first N ids)
        int n = Math.min(records.size(), 25);
        String seq = records.subList(0, n).stream()
                .map(r -> mapSyscallName(r.syscall_id()))
                .collect(Collectors.joining(" → "));

        return "record_count=" + records.size() + "\n" +
                "arg_class_counts=" + "FILE=" + fileOps + " NET=" + netOps + " PROC=" + procOps + " MEM=" + memOps + " OTHER=" + otherOps + "\n" +
                "top_syscalls=" + topSyscalls + "\n" +
                "sequence_sample=" + seq;
    }

    private String mapSyscallName(int id) {
        // Minimal mapping for demo readability; unknowns are numeric.
        return switch (id) {
            case 59 -> "execve";
            case 2, 257 -> "open";
            case 0 -> "read";
            case 1 -> "write";
            case 3 -> "close";
            case 42 -> "connect";
            case 41 -> "socket";
            default -> "sys_" + id;
        };
    }
}


