//! Line-oriented host transport for JVM regression tests of the real Rust policy.
use std::io::{self, BufRead, Write};

fn main() {
    let mut output = io::BufWriter::new(io::stdout().lock());
    for line in io::stdin().lock().lines() {
        let line = line.expect("policy input");
        let result = zerocon_engine::assist_policy::dispatch(&line)
            .unwrap_or_else(|error| serde_json::json!({"error": error}));
        writeln!(output, "{result}").expect("policy output");
        output.flush().expect("flush policy output");
    }
}
