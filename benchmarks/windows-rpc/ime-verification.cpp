// A fresh Win32 EDIT host for manual TSF/IME verification after a DLL update.
// No automation, logging, networking, profile changes, or persistent user data.
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <objbase.h>

static HWND edit;
static HFONT font;
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

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
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
    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) { TranslateMessage(&message); DispatchMessageW(&message); }
    CoUninitialize();
    return 0;
}
