import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Just Chat",
  description: "Agora RTC Web SDK 4.x one-to-one audio and video calls.",
  manifest: "/manifest.json",
  applicationName: "JustChat",
  appleWebApp: {
    capable: true,
    title: "JustChat",
    statusBarStyle: "black-translucent"
  },
  icons: {
    icon: "/icon-192.png",
    apple: "/icon-192.png"
  }
};

export const viewport: Viewport = {
  themeColor: "#4f46e5",
  width: "device-width",
  initialScale: 1
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        {children}
        <script
          dangerouslySetInnerHTML={{
            __html:
              "if ('serviceWorker' in navigator) { window.addEventListener('load', function () { navigator.serviceWorker.register('/sw.js'); }); }"
          }}
        />
      </body>
    </html>
  );
}
