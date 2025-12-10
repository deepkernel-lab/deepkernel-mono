#include <gtest/gtest.h>
#include <sstream>

#include "event_types.h"
#include "serialization.h"

TEST(TraceRecordTest, DeltaAndFields) {
    SyscallEvent first{
        .tsNs = 1'000'000ULL,
        .pid = 1,
        .tid = 1,
        .cgroupId = 10,
        .syscallId = 42,
        .argClass = 3,
        .argBucket = 7,
    };
    SyscallEvent second = first;
    second.tsNs = first.tsNs + 23'000; // 23 us

    auto rec = makeTraceRecord(second, first.tsNs);
    EXPECT_EQ(rec.delta_ts_us, 23u);
    EXPECT_EQ(rec.syscall_id, second.syscallId);
    EXPECT_EQ(rec.arg_class, second.argClass);
    EXPECT_EQ(rec.arg_bucket, second.argBucket);
}

TEST(TraceHeaderTest, PackedLayout) {
    TraceHeader hdr{};
    hdr.version = 1;
    hdr.syscall_vocab_size = 256;
    std::snprintf(hdr.container_id, sizeof(hdr.container_id), "%s", "prod/test");
    hdr.start_ts_ns = 123456789ULL;

    std::ostringstream oss;
    oss.write(reinterpret_cast<const char*>(&hdr), sizeof(hdr));
    std::string bytes = oss.str();
    ASSERT_EQ(bytes.size(), sizeof(TraceHeader));

    const TraceHeader* back = reinterpret_cast<const TraceHeader*>(bytes.data());
    EXPECT_EQ(back->version, 1u);
    EXPECT_EQ(back->syscall_vocab_size, 256u);
    EXPECT_STREQ(back->container_id, "prod/test");
    EXPECT_EQ(back->start_ts_ns, 123456789ULL);
}

TEST(WindowJsonTest, SerializesRecords) {
    std::vector<SyscallEvent> events;
    SyscallEvent e1{.tsNs = 1'000'000ULL, .pid = 1, .tid = 1, .cgroupId = 1, .syscallId = 5, .argClass = 2, .argBucket = 1};
    SyscallEvent e2 = e1;
    e2.tsNs = e1.tsNs + 50'000; // 50us
    events.push_back(e1);
    events.push_back(e2);

    std::string json = buildWindowJson("agent-1", "container-1", e1.tsNs, events);
    EXPECT_NE(json.find("\"agent_id\":\"agent-1\""), std::string::npos);
    EXPECT_NE(json.find("\"container_id\":\"container-1\""), std::string::npos);
    EXPECT_NE(json.find("\"delta_ts_us\":0"), std::string::npos);
    EXPECT_NE(json.find("\"delta_ts_us\":50"), std::string::npos);
    EXPECT_NE(json.find("\"syscall_id\":5"), std::string::npos);
    EXPECT_NE(json.find("\"arg_class\":2"), std::string::npos);
    EXPECT_NE(json.find("\"arg_bucket\":1"), std::string::npos);
}

