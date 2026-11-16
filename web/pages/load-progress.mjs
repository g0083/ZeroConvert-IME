// Count streamed bytes without buffering a second copy of the asset.
export async function trackAsset(asset, load, report) {
    if (!report) return load();
    report({ asset, phase: 'downloading', received: 0, total: null });
    const response = await load();
    if (!response.ok || !response.body) return response;
    const encoded = response.headers.get('Content-Encoding');
    const length = Number(response.headers.get('Content-Length'));
    // Fetch decodes HTTP compression before exposing its body.
    const total = (!encoded || encoded === 'identity') && Number.isSafeInteger(length) && length > 0 ? length : null;
    const reader = response.body.getReader();
    let received = 0;
    let lastReport = -Infinity;
    const stream = new ReadableStream({
        async pull(controller) {
            const { value, done } = await reader.read();
            if (done) {
                report({ asset, phase: 'downloaded', received });
                controller.close();
                return;
            }
            received += value.byteLength;
            const now = performance.now();
            if (now - lastReport >= 100) {
                report({ asset, phase: 'downloading', received, total: received <= total ? total : null });
                lastReport = now;
            }
            controller.enqueue(value);
        },
        cancel(reason) { return reader.cancel(reason); },
    });
    return new Response(stream, { headers: response.headers, status: response.status });
}
