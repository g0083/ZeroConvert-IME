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

/// Describes the assist line shown beside the reading.
///
/// A correction renders as a diff from the reading it replaced; the correction
/// answer already carries the token reading/surface pair the Windows ruby view
/// diffs. A prediction renders as a plain continuation. Anything that arrived
/// for another session or another composition is dropped.
export function assistLine(message, ids, session, reading) {
    if (!message || message.session !== session || message.reading !== reading) return null;
    if (ids.typoCorrection !== undefined && message.displayedAssist === ids.typoCorrection) {
        const correction = message.answer;
        if (!correction?.text || correction.text === reading) return null;
        return { kind: 'correction', text: correction.text, source: correction.tokenReading || reading };
    }
    if (ids.predictive !== undefined && message.displayedAssist === ids.predictive) {
        const text = message.answer?.continuation;
        return text ? { kind: 'prediction', text, source: null } : null;
    }
    return null;
}
