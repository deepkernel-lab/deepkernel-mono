#pragma once

#include <string>

class HttpClient {
public:
    // Post JSON with single attempt
    bool postJson(const std::string& url, const std::string& body, long timeoutSeconds = 5) const;

    // Post JSON with retry logic (exponential backoff)
    bool postJsonWithRetry(const std::string& url, const std::string& body, 
                           int maxRetries = 3, long timeoutSeconds = 5) const;
};

