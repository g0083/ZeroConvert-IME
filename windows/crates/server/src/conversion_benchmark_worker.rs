use std::{
    ffi::{c_char, c_void, CStr, CString},
    io::{self, BufRead, Write},
};

unsafe extern "C" {
    fn ZeroConOpenConversionBenchmark(
        model: *const c_char,
        dictionary: *const c_char,
        gpu_layers: i32,
    ) -> *mut c_void;
    fn ZeroConRunConversionBenchmark(
        session: *mut c_void,
        reading: *const c_char,
        context: *const c_char,
    ) -> *mut c_char;
    fn ZeroConCloseConversionBenchmark(session: *mut c_void);
}

struct Session(*mut c_void);

impl Drop for Session {
    fn drop(&mut self) {
        unsafe { ZeroConCloseConversionBenchmark(self.0) }
    }
}

pub fn serve() -> Result<(), Box<dyn std::error::Error>> {
    let mut lines = io::stdin().lock().lines();
    let mut next_line = || -> io::Result<String> {
        lines
            .next()
            .transpose()?
            .ok_or(io::ErrorKind::UnexpectedEof.into())
    };
    let model = CString::new(next_line()?)?;
    let dictionary = CString::new(next_line()?)?;
    let gpu_layers: i32 = next_line()?.parse()?;
    let session =
        unsafe { ZeroConOpenConversionBenchmark(model.as_ptr(), dictionary.as_ptr(), gpu_layers) };
    if session.is_null() {
        return Err("candidate benchmark initialization failed".into());
    }
    let session = Session(session);
    // Lock stdout only while writing. The converter may use it for diagnostics.
    println!("benchmark:ready");
    for line in lines {
        let (reading, context): (String, String) = serde_json::from_str(&line?)?;
        let reading = CString::new(reading)?;
        let context = CString::new(context)?;
        let result =
            unsafe { ZeroConRunConversionBenchmark(session.0, reading.as_ptr(), context.as_ptr()) };
        let mut output = io::stdout().lock();
        output.write_all(b"benchmark:")?;
        if result.is_null() {
            output.write_all(b"null")?;
        } else {
            let write_result = unsafe { output.write_all(CStr::from_ptr(result).to_bytes()) };
            unsafe { super::FreeCString(result) };
            write_result?;
        }
        writeln!(output)?;
        output.flush()?;
    }
    Ok(())
}
