#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <thread>

// Simple HTTP server for receiving control commands from DeepKernel server.
// Handles:
//   POST /long-dump-requests - trigger long dump
//   POST /policies - receive and apply policy
//   GET /health - health check

class AgentServer {
public:
    using LongDumpHandler = std::function<void(const std::string& containerId, int durationSec, const std::string& reason)>;
    using PolicyHandler = std::function<bool(const std::string& containerId, const std::string& policyId, 
                                             const std::string& policyType, const std::string& specJson)>;

    explicit AgentServer(int port);
    ~AgentServer();

    void setLongDumpHandler(LongDumpHandler handler);
    void setPolicyHandler(PolicyHandler handler);

    bool start();
    void stop();
    bool isRunning() const;

private:
    int port_;
    int serverFd_{-1};
    std::atomic<bool> running_{false};
    std::thread serverThread_;

    LongDumpHandler longDumpHandler_;
    PolicyHandler policyHandler_;

    void serverLoop();
    void handleClient(int clientFd);

    // Simple HTTP parsing helpers
    struct HttpRequest {
        std::string method;
        std::string path;
        std::string body;
    };

    HttpRequest parseRequest(const std::string& raw);
    void sendResponse(int clientFd, int statusCode, const std::string& statusText, const std::string& body);

    // JSON parsing helpers (minimal)
    std::string extractJsonString(const std::string& json, const std::string& key);
    int extractJsonInt(const std::string& json, const std::string& key, int defaultVal);
};

