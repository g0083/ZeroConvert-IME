// Correction and prediction arbitration shared by the Web Worker and its tests.
//
// The rules themselves live in the shared Rust assist policy, which the Worker
// loads as `zerocon_session_assist`. This module only decides *when* to ask and
// *which* late answer to discard, so a fixed-response test can pin down arrival
// order without a model.

/// Candidate identities come from the shared policy, never from a local copy.
export function assistIds(live) {
    const ids = live.assist({ op: 'ids' });
    return { typoCorrection: ids.typoCorrection, predictive: ids.predictive };
}

/// Asks the shared plan whether correction and prediction should run.
export function planAssist(live, settings, correction, reading, baselineReading = '') {
    return live.assist({
        op: 'plan',
        correctionEnabled: settings.correction,
        predictionEnabled: settings.prediction,
        applied: Boolean(correction),
        reading,
        baselineReading,
    });
}

/// Merges the answers that arrived for `requested`.
///
/// `generation` is the Worker's current edit generation. An answer requested
/// before the last keystroke is stale: adopting it would rewrite text the user
/// has already moved past, so it is dropped. `baseline` is the normal candidate
/// currently shown; an assist answer equal to it adds nothing and is dropped so
/// normal conversion keeps ownership of the composition.
///
/// The result is presentational only. Committing an assist candidate needs the
/// shared `adopt` decision, which the Worker exposes but does not yet act on:
/// nothing here may rewrite committed text on its own.
export function mergeAssist(live, generation, requested, answers, displayed, baseline) {
    if (requested !== generation) return null;
    const ids = assistIds(live);
    const known = answer => answer?.id === ids.typoCorrection || answer?.id === ids.predictive;
    const eligible = answers.filter(answer => answer?.text && answer.text !== baseline && known(answer));
    const choices = eligible.map(answer => [answer.relevance ?? 0, answer.id]);
    if (!choices.length) return null;
    const current = eligible.some(answer => answer.id === displayed) ? displayed : null;
    const selected = live.assist({ op: 'choose', choices, displayed: current }).selected;
    return eligible.find(answer => answer.id === selected) ?? null;
}

/// Maps a current offer to the Windows reading window's presentation envelope.
export function assistLine(message, ids, session, reading) {
    if (!message || message.session !== session || message.reading !== reading) return null;
    const { answer } = message;
    if (!answer?.text || message.displayedAssist !== answer.id) return null;
    if (answer.id === ids.typoCorrection) {
        const baseline = message.state?.candidates[0];
        const source = baseline ? baseline.text + baseline.suffix : reading;
        if (answer.text === source) return null;
        return { status: 'proposal', replacement: answer.text, source };
    }
    if (answer.id === ids.predictive && answer.continuation) {
        return { status: 'prediction', replacement: answer.text, continuation: answer.continuation };
    }
    return null;
}
