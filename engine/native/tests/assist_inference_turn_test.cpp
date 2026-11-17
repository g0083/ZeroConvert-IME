#include "../assist_inference_turn.h"

#include <cassert>
#include <future>

using namespace std::chrono_literals;
using Clock = std::chrono::steady_clock;

int main() {
    std::timed_mutex generation_mutex;
    std::atomic<long long> epoch{1};
    auto start_request = [&](long long input_epoch, std::chrono::milliseconds budget) {
        return std::async(std::launch::async, [&, input_epoch, budget] {
            std::unique_lock lock(generation_mutex, std::defer_lock);
            return wait_for_assist_inference(lock, epoch, input_epoch, Clock::now() + budget);
        });
    };

    // Prediction already holds the model. Correction can use it once prediction yields.
    std::unique_lock prediction(generation_mutex);
    auto correction = start_request(1, 2s);
    assert(correction.wait_for(50ms) == std::future_status::timeout);
    prediction.unlock();
    assert(correction.get());

    // A request whose deadline expires while the model is occupied must not decode.
    prediction.lock();
    auto expired = start_request(1, 25ms);
    assert(!expired.get());

    // A key edit cancels an old request even if the inference slot stays occupied.
    auto stale = start_request(1, 2s);
    assert(stale.wait_for(50ms) == std::future_status::timeout);
    epoch.fetch_add(1);
    assert(stale.wait_for(1s) == std::future_status::ready);
    assert(!stale.get());
    prediction.unlock();
    assert(start_request(2, 2s).get());
}
