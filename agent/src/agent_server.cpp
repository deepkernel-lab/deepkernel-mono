#include "agent_server.h"

#include <arpa/inet.h>
#include <cstring>
#include <iostream>
#include <netinet/in.h>
#include <regex>
#include <sstream>
#include <sys/socket.h>
#include <unistd.h>

AgentServer::AgentServer(int port) : port_(port) {}

AgentServer::~AgentServer() {
    stop();
}

void AgentServer::setLongDumpHandler(LongDumpHandler handler) {
    longDumpHandler_ = std::move(handler);
}

void AgentServer::setPolicyHandler(PolicyHandler handler) {
    policyHandler_ = std::move(handler);
}

bool AgentServer::start() {
    serverFd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (serverFd_ < 0) {
        std::cerr << "AgentServer: Failed to create socket\n";
        return false;
    }

    // Allow reuse of address
    int opt = 1;
    setsockopt(serverFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port_);

    if (bind(serverFd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::cerr << "AgentServer: Failed to bind to port " << port_ << "\n";
        close(serverFd_);
        serverFd_ = -1;
        return false;
    }

    if (listen(serverFd_, 10) < 0) {
        std::cerr << "AgentServer: Failed to listen\n";
        close(serverFd_);
        serverFd_ = -1;
        return false;
    }

    running_ = true;
    serverThread_ = std::thread(&AgentServer::serverLoop, this);

    std::cout << "AgentServer: Listening on port " << port_ << "\n";
    return true;
}

void AgentServer::stop() {
    running_ = false;
    if (serverFd_ >= 0) {
        shutdown(serverFd_, SHUT_RDWR);
        close(serverFd_);
        serverFd_ = -1;
    }
    if (serverThread_.joinable()) {
        serverThread_.join();
    }
}

bool AgentServer::isRunning() const {
    return running_;
}

void AgentServer::serverLoop() {
    while (running_) {
        // Use select with timeout for graceful shutdown
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(serverFd_, &readSet);

        timeval timeout{};
        timeout.tv_sec = 1;
        timeout.tv_usec = 0;

        int result = select(serverFd_ + 1, &readSet, nullptr, nullptr, &timeout);
        if (result < 0) {
            if (running_) {
                std::cerr << "AgentServer: select error\n";
            }
            break;
        }
        if (result == 0) {
            continue;  // Timeout, check running_ flag
        }

        sockaddr_in clientAddr{};
        socklen_t clientLen = sizeof(clientAddr);
        int clientFd = accept(serverFd_, reinterpret_cast<sockaddr*>(&clientAddr), &clientLen);
        if (clientFd < 0) {
            if (running_) {
                std::cerr << "AgentServer: accept error\n";
            }
            continue;
        }

        handleClient(clientFd);
        close(clientFd);
    }
}

void AgentServer::handleClient(int clientFd) {
    char buffer[8192];
    ssize_t bytesRead = recv(clientFd, buffer, sizeof(buffer) - 1, 0);
    if (bytesRead <= 0) {
        return;
    }
    buffer[bytesRead] = '\0';

    HttpRequest req = parseRequest(std::string(buffer, bytesRead));

    // Route requests
    if (req.method == "GET" && req.path == "/health") {
        sendResponse(clientFd, 200, "OK", "{\"status\":\"healthy\"}");
    } else if (req.method == "POST" && req.path == "/long-dump-requests") {
        std::string containerId = extractJsonString(req.body, "container_id");
        int durationSec = extractJsonInt(req.body, "duration_sec", 1200);
        std::string reason = extractJsonString(req.body, "reason");

        if (containerId.empty()) {
            sendResponse(clientFd, 400, "Bad Request", "{\"error\":\"container_id required\"}");
            return;
        }

        if (longDumpHandler_) {
            longDumpHandler_(containerId, durationSec, reason);
        }
        sendResponse(clientFd, 202, "Accepted", "{\"status\":\"dump_requested\"}");

    } else if (req.method == "POST" && req.path == "/policies") {
        std::string containerId = extractJsonString(req.body, "container_id");
        std::string policyId = extractJsonString(req.body, "policy_id");
        std::string policyType = extractJsonString(req.body, "type");

        if (containerId.empty() || policyId.empty()) {
            sendResponse(clientFd, 400, "Bad Request", "{\"error\":\"container_id and policy_id required\"}");
            return;
        }

        bool success = true;
        if (policyHandler_) {
            success = policyHandler_(containerId, policyId, policyType, req.body);
        }

        if (success) {
            sendResponse(clientFd, 200, "OK", "{\"status\":\"APPLIED\",\"policy_id\":\"" + policyId + "\"}");
        } else {
            sendResponse(clientFd, 500, "Internal Server Error", "{\"status\":\"FAILED\",\"policy_id\":\"" + policyId + "\"}");
        }

    } else {
        sendResponse(clientFd, 404, "Not Found", "{\"error\":\"not found\"}");
    }
}

AgentServer::HttpRequest AgentServer::parseRequest(const std::string& raw) {
    HttpRequest req;
    std::istringstream stream(raw);
    std::string line;

    // Parse request line: METHOD PATH HTTP/1.x
    if (std::getline(stream, line)) {
        std::istringstream lineStream(line);
        lineStream >> req.method >> req.path;
    }

    // Skip headers until empty line
    while (std::getline(stream, line) && line != "\r" && !line.empty()) {
        // Could parse Content-Length here if needed
    }

    // Rest is body
    std::ostringstream bodyStream;
    while (std::getline(stream, line)) {
        bodyStream << line;
    }
    req.body = bodyStream.str();

    return req;
}

void AgentServer::sendResponse(int clientFd, int statusCode, const std::string& statusText, const std::string& body) {
    std::ostringstream response;
    response << "HTTP/1.1 " << statusCode << " " << statusText << "\r\n";
    response << "Content-Type: application/json\r\n";
    response << "Content-Length: " << body.size() << "\r\n";
    response << "Connection: close\r\n";
    response << "\r\n";
    response << body;

    std::string responseStr = response.str();
    send(clientFd, responseStr.c_str(), responseStr.size(), 0);
}

std::string AgentServer::extractJsonString(const std::string& json, const std::string& key) {
    // Simple JSON string extraction: "key":"value" or "key": "value"
    std::string pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
    std::regex re(pattern);
    std::smatch match;
    if (std::regex_search(json, match, re) && match.size() > 1) {
        return match[1].str();
    }
    return "";
}

int AgentServer::extractJsonInt(const std::string& json, const std::string& key, int defaultVal) {
    // Simple JSON int extraction: "key":123 or "key": 123
    std::string pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
    std::regex re(pattern);
    std::smatch match;
    if (std::regex_search(json, match, re) && match.size() > 1) {
        try {
            return std::stoi(match[1].str());
        } catch (...) {
            return defaultVal;
        }
    }
    return defaultVal;
}

