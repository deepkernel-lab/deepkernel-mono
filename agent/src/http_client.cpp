#include "http_client.h"

#include <chrono>
#include <curl/curl.h>
#include <iostream>
#include <thread>

bool HttpClient::postJson(const std::string& url, const std::string& body, long timeoutSeconds) const {
    CURL* curl = curl_easy_init();
    if (!curl) {
        std::cerr << "Failed to init curl\n";
        return false;
    }

    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, timeoutSeconds);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);  // Thread-safe

    CURLcode res = curl_easy_perform(curl);
    if (res != CURLE_OK) {
        std::cerr << "curl_easy_perform() failed: " << curl_easy_strerror(res) << "\n";
    }

    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    return res == CURLE_OK;
}

bool HttpClient::postJsonWithRetry(const std::string& url, const std::string& body,
                                   int maxRetries, long timeoutSeconds) const {
    for (int attempt = 0; attempt < maxRetries; ++attempt) {
        if (postJson(url, body, timeoutSeconds)) {
            return true;
        }

        // Exponential backoff: 100ms, 200ms, 400ms, ...
        if (attempt < maxRetries - 1) {
            int delayMs = 100 * (1 << attempt);
            std::this_thread::sleep_for(std::chrono::milliseconds(delayMs));
            std::cerr << "Retry " << (attempt + 1) << "/" << maxRetries << " for " << url << "\n";
        }
    }
    return false;
}

