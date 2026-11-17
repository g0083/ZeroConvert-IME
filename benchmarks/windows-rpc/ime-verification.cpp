// A fresh Win32 EDIT host for manual TSF/IME verification after a DLL update.
// No input injection, logging, networking, or persistent user data.
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <msctf.h>
#include <objbase.h>
#include <cwchar>

static HWND edit;
static HFONT font;

// The registered descriptors match windows/crates/client/src/globals.rs.
// Activate only in this test process; do not enable or register a profile.
static HRESULT select_zero_convert() {
    static const CLSID service = {0xffdefe79, 0x2fc2, 0x11ef, {0xb1, 0x6b, 0x94, 0xe7, 0x0b, 0x2c, 0x37, 0x8c}};
    static const GUID profile = {0xffdefe7a, 0x2fc2, 0x11ef, {0xb1, 0x6b, 0x94, 0xe7, 0x0b, 0x2c, 0x37, 0x8c}};
    ITfInputProcessorProfileMgr *manager = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_PPV_ARGS(&manager));
    if (FAILED(hr)) return hr;
    hr = manager->ActivateProfile(TF_PROFILETYPE_INPUTPROCESSOR, 0x0411, service, profile,
                                  nullptr, TF_IPPMF_FORPROCESS);
    if (hr == S_OK) {
        TF_INPUTPROCESSORPROFILE active{};
        hr = manager->GetActiveProfile(GUID_TFCAT_TIP_KEYBOARD, &active);
        if (hr == S_OK && (!IsEqualGUID(active.clsid, service) || !IsEqualGUID(active.guidProfile, profile))) {
            hr = E_UNEXPECTED;
        }
    }
    manager->Release();
    return hr;
}
LRESULT CALLBACK procedure(HWND window, UINT message, WPARAM w, LPARAM l) {
    switch (message) {
    case WM_CREATE:
        CreateWindowW(L"STATIC", L"ZeroConvert IME verification: Tab = adopt, Esc = cancel, Enter = commit", WS_CHILD|WS_VISIBLE,
                      10, 10, 900, 25, window, nullptr, nullptr, nullptr);
        edit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD|WS_VISIBLE|WS_TABSTOP|WS_VSCROLL|
                              ES_LEFT|ES_MULTILINE|ES_AUTOVSCROLL|ES_WANTRETURN,
                              10, 40, 900, 500, window, reinterpret_cast<HMENU>(1), nullptr, nullptr);
        font = CreateFontW(-24, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                           OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Yu Gothic UI");
        SendMessageW(edit, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
        SetFocus(edit);
        return 0;
    case WM_SIZE:
        MoveWindow(edit, 10, 40, LOWORD(l)-20, HIWORD(l)-50, TRUE);
        return 0;
    case WM_SETFOCUS:
        SetFocus(edit);
        return 0;
    case WM_DESTROY:
        DeleteObject(font);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(window, message, w, l);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR arguments, int show) {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    WNDCLASSW cls{};
    cls.lpfnWndProc = procedure;
    cls.hInstance = instance;
    cls.lpszClassName = L"ZeroConvertIMEVerification";
    cls.hCursor = LoadCursorW(nullptr, IDC_IBEAM);
    cls.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);
    if (!RegisterClassW(&cls)) return 1;
    HWND window = CreateWindowW(cls.lpszClassName, L"ZeroConvert IME - hybrid verification", WS_OVERLAPPEDWINDOW,
                                CW_USEDEFAULT, CW_USEDEFAULT, 1000, 650, nullptr, nullptr, instance, nullptr);
    if (!window) return 2;
    ShowWindow(window, show);
    UpdateWindow(window);
    if (std::wcscmp(arguments, L"--system-ime") != 0) {
        const auto result = select_zero_convert();
        const wchar_t *title = result == S_OK ? L"ZeroConvert IME - active TSF verification"
                                              : L"ZeroConvert IME - could not select TSF profile";
        SetWindowTextW(window, title);
    }
    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) { TranslateMessage(&message); DispatchMessageW(&message); }
    CoUninitialize();
    return 0;
}
