package com.deepkernel.core.service;

import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ShortWindowPayload;
import com.deepkernel.contracts.model.TraceRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the 594-dim feature vector described in MASTER_PLAN.md Section 7 from a short window.
 * This implementation is simplified but preserves vector layout: [K*K markov][stats][time][args].
 */
@Component
public class FeatureExtractor {
    private static final int K = 24;
    private static final int BUCKETS = 12;
    private static final int FEATURE_DIM = 594;

    public FeatureVector extract(ShortWindowPayload payload) {
        List<TraceRecord> records = payload.records();
        if (records == null || records.isEmpty()) {
            return new FeatureVector(List.of());
        }

        double[][] transitions = new double[K][K];
        int[] outgoing = new int[K];
        int[] bucketCounts = new int[BUCKETS];
        int fileOps = 0, netOps = 0, procOps = 0, otherOps = 0;

        long firstTs = payload.windowStartTsNs();
        long lastTs = firstTs;
        int total = records.size();

        for (int i = 0; i < total; i++) {
            TraceRecord r = records.get(i);
            int currId = mapSyscallToAlphabet(r.syscallId());
            int bucket = mapBucket(r.argClass(), r.argBucket());
            bucketCounts[bucket]++;

            if (r.argClass() == 0) fileOps++;
            else if (r.argClass() == 1) netOps++;
            else if (r.argClass() == 2) procOps++;
            else otherOps++;

            long ts = payload.windowStartTsNs() + (long) r.deltaTsUs() * 1000L;
            lastTs = ts;

            if (i > 0) {
                TraceRecord prev = records.get(i - 1);
                int prevId = mapSyscallToAlphabet(prev.syscallId());
                transitions[prevId][currId] += 1.0;
                outgoing[prevId] += 1;
            }
        }

        // Normalize transitions rows
        for (int i = 0; i < K; i++) {
            if (outgoing[i] == 0) continue;
            for (int j = 0; j < K; j++) {
                transitions[i][j] /= outgoing[i];
            }
        }

        List<Float> vec = new ArrayList<>(FEATURE_DIM);
        int uniqueTwoGrams = 0;
        double entropy = 0.0;
        int totalTransitions = Math.max(total - 1, 1);

        for (int i = 0; i < K; i++) {
            for (int j = 0; j < K; j++) {
                double v = transitions[i][j];
                vec.add((float) v);
                if (v > 0) {
                    uniqueTwoGrams++;
                    double count = v * outgoing[i];
                    double p = count / totalTransitions;
                    entropy -= p * Math.log(p + 1e-12);
                }
            }
        }

        float fileRatio = total == 0 ? 0 : (float) fileOps / total;
        float netRatio = total == 0 ? 0 : (float) netOps / total;
        double durationSec = (lastTs - firstTs) / 1_000_000_000.0;
        double meanInterArrival = total > 0 ? durationSec / total : 0.0;

        vec.add((float) uniqueTwoGrams);
        vec.add((float) entropy);
        vec.add(fileRatio);
        vec.add(netRatio);
        vec.add((float) durationSec);
        vec.add((float) meanInterArrival);

        int bucketTotal = Math.max(total, 1);
        for (int b = 0; b < BUCKETS; b++) {
            vec.add((float) bucketCounts[b] / bucketTotal);
        }

        // Pad if shorter (defensive)
        while (vec.size() < FEATURE_DIM) {
            vec.add(0.0f);
        }
        if (vec.size() > FEATURE_DIM) {
            vec = vec.subList(0, FEATURE_DIM);
        }

        return new FeatureVector(vec);
    }

    private int mapSyscallToAlphabet(int syscallIdRaw) {
        // Simplified mapping; extend per MASTER_PLAN as needed.
        switch (syscallIdRaw) {
            case 59: // execve
                return 0;
            case 2: // open
            case 257: // openat
                return 1;
            case 0: // read
                return 2;
            case 1: // write
                return 3;
            case 3: // close
                return 4;
            case 4: // stat
            case 5: // fstat
                return 5;
            case 42: // connect
                return 6;
            case 43: // accept
                return 7;
            case 44: // sendto
            case 46: // sendmsg
                return 8;
            case 45: // recvfrom
            case 47: // recvmsg
                return 9;
            case 41: // socket
            case 49: // bind
            case 50: // listen
                return 10;
            case 57: // fork
            case 56: // clone
                return 11;
            case 9: // mmap
            case 10: // mprotect
                return 12;
            case 90: // chmod
            case 91: // fchmod
                return 13;
            case 87: // unlink
            case 84: // rmdir
                return 14;
            case 82: // rename
                return 15;
            case 102: // getuid
            case 105: // setuid
                return 16;
            case 160: // setrlimit
                return 17;
            case 101: // ptrace
                return 18;
            case 165: // mount
            case 166: // umount
                return 19;
            case 12: // brk
                return 20;
            case 78: // getdents
                return 21;
            case 162: // nanosleep
                return 22;
            default:
                return 23; // OTHER
        }
    }

    private int mapBucket(int argClass, int argBucket) {
        // Simple folding into BUCKETS slots.
        int idx = (argClass * 4 + argBucket) % BUCKETS;
        if (idx < 0 || idx >= BUCKETS) {
            return BUCKETS - 1;
        }
        return idx;
    }
}

