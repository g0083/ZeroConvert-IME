// Persist the Swift learner's files, keeping different tabs from overwriting
// a snapshot they did not load.
const MAX_BYTES = 16 * 1024 * 1024;
const MAX_FILES = 128;
const validName = name => typeof name === 'string' && /^(?!\.{1,2}$)[a-z0-9_.-]{1,128}$/iu.test(name);
const versionOf = saved => Number.isSafeInteger(saved?.version) && saved.version >= 0 ? saved.version : 0;

export function validLearningFiles(files) {
    if (!Array.isArray(files) || files.length > MAX_FILES) return false;
    const names = new Set();
    let bytes = 0;
    return files.every(entry => {
        if (!Array.isArray(entry) || entry.length !== 2) return false;
        const [name, value] = entry;
        if (!validName(name) || names.has(name) || !(value instanceof Uint8Array)) return false;
        names.add(name);
        bytes += value.byteLength;
        return bytes <= MAX_BYTES;
    });
}

export function openLearningStore(database = globalThis.indexedDB) {
    if (!database) return Promise.resolve(null);
    return new Promise((resolve, reject) => {
        const open = database.open('zerocon-conversion-learning', 1);
        open.onupgradeneeded = () => open.result.createObjectStore('engine');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const db = open.result;
            db.onversionchange = () => db.close();
            resolve({
                read() {
                    return new Promise((done, fail) => {
                        const tx = db.transaction('engine');
                        const read = tx.objectStore('engine').get('snapshot');
                        tx.onabort = () => fail(tx.error);
                        tx.oncomplete = () => {
                            const saved = read.result;
                            done({ version: versionOf(saved), files: validLearningFiles(saved?.files) ? saved.files : [] });
                        };
                    });
                },
                save(version, files) {
                    if (!validLearningFiles(files)) return Promise.reject(new Error('Invalid learning snapshot'));
                    return new Promise((done, fail) => {
                        const tx = db.transaction('engine', 'readwrite');
                        const store = tx.objectStore('engine');
                        const read = store.get('snapshot');
                        let next = null;
                        read.onsuccess = () => {
                            if (versionOf(read.result) !== version) return;
                            next = version + 1;
                            store.put({ version: next, files }, 'snapshot');
                        };
                        tx.onabort = () => fail(tx.error);
                        tx.oncomplete = () => done(next);
                    });
                },
            });
        };
    });
}
