const repository = 'g0083/ZeroConvert-IME';

export async function loadLatestDownloads(request = fetch) {
    try {
        const response = await request(`https://api.github.com/repos/${repository}/releases/latest`, { cache: 'no-cache' });
        if (!response.ok) return {};
        const { tag_name: tag, assets } = await response.json();
        if (typeof tag !== 'string' || !tag || !Array.isArray(assets)) return {};

        const versionedTag = tag.startsWith('v') ? tag : `v${tag}`;
        const filenames = {
            windows: `ZeroConvert-IME-setup-${versionedTag}.exe`,
            android: `ZeroConvert-IME-${versionedTag}-universal-debug.apk`,
        };
        const links = {};
        for (const [platform, filename] of Object.entries(filenames)) {
            if (assets.some(asset => asset?.name === filename && asset.state === 'uploaded')) {
                links[platform] = `https://github.com/${repository}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(filename)}`;
            }
        }
        return links;
    } catch {
        // Keep the server-rendered links to the latest release if the API is unavailable.
        return {};
    }
}

if (typeof document !== 'undefined') {
    void loadLatestDownloads().then(downloads => {
        for (const link of document.querySelectorAll('[data-platform-download]')) {
            const url = downloads[link.dataset.platformDownload];
            if (!url) continue;
            link.href = url;
            link.querySelector('[data-download-icon]').textContent = '↓';
        }
    });
}
