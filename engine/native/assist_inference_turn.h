#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>

inline bool wait_for_assist_inference(std::unique_lock<std::timed_mutex> &lock,
                                      const std::atomic<long long> &epoch,
                                      long long expected_epoch,
                                      std::chrono::steady_clock::time_point deadline) {
    using namespace std::chrono;
    while (epoch.load() == expected_epoch) {
        const auto now = steady_clock::now();
        if (now >= deadline) return false;
        // Check cancellation even while another request still owns the context.
        if (lock.try_lock_until(std::min(deadline, now + milliseconds(10)))) {
            return epoch.load() == expected_epoch && steady_clock::now() < deadline;
        }
    }
    return false;
}
