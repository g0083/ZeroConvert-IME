interface LogoProps {
    className?: string;
    size?: number;
}

export function ZeroConvertLogo({ className = "size-6", size }: LogoProps) {
    const style = size ? { width: size, height: size } : undefined;
    return (
        <svg
            viewBox="0 0 1024 1024"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            className={className}
            style={style}
            aria-label="ZeroConvert IME Logo"
        >
            <defs>
                <linearGradient id="zcBgGrad" x1="512" y1="52" x2="512" y2="972" gradientUnits="userSpaceOnUse">
                    <stop offset="0%" stopColor="#10121A" />
                    <stop offset="100%" stopColor="#1C202C" />
                </linearGradient>
                <linearGradient id="zcGlyphGrad" x1="300" y1="216" x2="724" y2="808" gradientUnits="userSpaceOnUse">
                    <stop offset="0%" stopColor="#FFFFFF" />
                    <stop offset="100%" stopColor="#E2E8F0" />
                </linearGradient>
            </defs>

            {/* Squircle Background */}
            <rect
                x="52"
                y="52"
                width="920"
                height="920"
                rx="216"
                fill="url(#zcBgGrad)"
            />

            {/* Subtle Titanium Satin Rim */}
            <rect
                x="52"
                y="52"
                width="920"
                height="920"
                rx="216"
                stroke="#FFFFFF"
                strokeOpacity="0.14"
                strokeWidth="6"
            />

            {/* Main Minimalist "0" Capsule Ring */}
            <rect
                x="296"
                y="216"
                width="432"
                height="592"
                rx="118"
                fill="url(#zcGlyphGrad)"
            />
            {/* Inner Cutout */}
            <rect
                x="364"
                y="284"
                width="296"
                height="456"
                rx="54"
                fill="url(#zcBgGrad)"
            />

            {/* 45-degree Conversion Bridge */}
            <polygon
                points="272,698 326,752 752,326 698,272"
                fill="url(#zcGlyphGrad)"
            />

            {/* Center Refined Titanium Pivot Node */}
            <circle
                cx="512"
                cy="512"
                r="26"
                fill="#64748B"
            />
            <circle
                cx="512"
                cy="512"
                r="23"
                stroke="#FFFFFF"
                strokeOpacity="0.25"
                strokeWidth="2"
                fill="none"
            />
        </svg>
    );
}
