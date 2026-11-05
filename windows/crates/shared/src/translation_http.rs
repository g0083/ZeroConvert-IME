//! Bounded loopback HTTP used only on background threads, never TSF callbacks.
use serde_json::Value;
use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpStream},
    time::{Duration, Instant},
};

pub fn request(port: u16, body: Option<&Value>, timeout: Duration) -> Option<(u16, Value)> {
    let deadline = Instant::now() + timeout;
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    let mut stream =
        TcpStream::connect_timeout(&address, timeout.min(Duration::from_millis(300))).ok()?;
    stream.set_write_timeout(Some(timeout)).ok()?;
    let payload = body.map(Value::to_string).unwrap_or_default();
    let (method, path) = if body.is_some() {
        ("POST", "/completion")
    } else {
        ("GET", "/health")
    };
    let header = format!("{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", payload.len());
    stream.write_all(header.as_bytes()).ok()?;
    stream.write_all(payload.as_bytes()).ok()?;
    let mut bytes = Vec::new();
    let mut chunk = [0u8; 4096];
    let mut parsed = None;
    loop {
        if let Some((offset, status, length)) = parsed {
            if bytes.len() >= offset + length {
                let value = serde_json::from_slice(&bytes[offset..offset + length]).ok()?;
                return Some((status, value));
            }
        }
        stream
            .set_read_timeout(Some(deadline.checked_duration_since(Instant::now())?))
            .ok()?;
        let count = stream.read(&mut chunk).ok()?;
        if count == 0 {
            return None;
        }
        bytes.extend_from_slice(&chunk[..count]);
        if bytes.len() > 128 * 1024 {
            return None;
        }
        if parsed.is_none() {
            if let Some(end) = bytes.windows(4).position(|part| part == b"\r\n\r\n") {
                let header = std::str::from_utf8(&bytes[..end]).ok()?;
                let status = header
                    .lines()
                    .next()?
                    .split_whitespace()
                    .nth(1)?
                    .parse::<u16>()
                    .ok()?;
                let length = header.lines().find_map(|line| {
                    let (name, value) = line.split_once(':')?;
                    name.eq_ignore_ascii_case("Content-Length")
                        .then(|| value.trim().parse::<usize>().ok())
                        .flatten()
                })?;
                if length > 120 * 1024 {
                    return None;
                }
                parsed = Some((end + 4, status, length));
            }
        }
    }
}

pub fn ready() -> bool {
    request(
        crate::TRANSLATION_SERVER_PORT,
        None,
        Duration::from_millis(300),
    )
    .is_some_and(|(status, value)| status == 200 && value["status"] == "ok")
}

pub fn completion(source: &str, target: &str) -> Value {
    let source_lang = if target == "en" { "ja" } else { "en" };
    serde_json::json!({"prompt":format!("<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>{source_lang}<|plamo:msg|>{source}<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>{target}<|plamo:msg|>"),
        "n_predict":512,"temperature":0.0,"stream":false,"cache_prompt":true,
        "stop":["<|plamo:bos|>","<|plamo:tag|>"]})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn content_length_finishes_without_waiting_for_connection_close() {
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let (done, wait) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buffer = [0u8; 2048];
            let _ = stream.read(&mut buffer).unwrap();
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: keep-alive\r\n\r\n{\"status\":\"ok\"}").unwrap();
            let _ = wait.recv_timeout(Duration::from_secs(2));
        });
        let response = request(port, None, Duration::from_secs(1));
        done.send(()).unwrap();
        worker.join().unwrap();
        assert_eq!(response.unwrap(), (200, serde_json::json!({"status":"ok"})));
    }
}
