package com.deepkernel.core.service;

import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ShortWindowPayload;
import com.deepkernel.contracts.model.TraceRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeatureExtractorTest {

    @Test
    void buildsFeatureVectorWithExpectedLengthAndValues() {
        FeatureExtractor extractor = new FeatureExtractor();
        ShortWindowPayload payload = new ShortWindowPayload(
                1,
                "agent-1",
                "container-1",
                1_000_000L,
                List.of(
                        new TraceRecord(0, 59, 2, 1),   // execve
                        new TraceRecord(100, 2, 0, 0)   // read
                )
        );

        FeatureVector fv = extractor.extract(payload);
        assertNotNull(fv);
        assertEquals(594, fv.values().size(), "feature length");

        // First Markov cell should reflect a single transition.
        float firstCell = fv.values().get(0);
        assertTrue(firstCell >= 0.0f);

        // uniqueTwoGrams (index 576) should be > 0
        float unique = fv.values().get(576);
        assertTrue(unique >= 1.0f);

        // file/net ratios (indexes 578, 579) within [0,1]
        float fileRatio = fv.values().get(578);
        float netRatio = fv.values().get(579);
        assertTrue(fileRatio >= 0.0f && fileRatio <= 1.0f);
        assertTrue(netRatio >= 0.0f && netRatio <= 1.0f);
    }
}

