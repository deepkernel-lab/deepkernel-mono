#pragma once

#include <string>

class HttpClient {
public:
    bool postJson(const std::string& url, const std::string& body, long timeoutSeconds = 5) const;
};

