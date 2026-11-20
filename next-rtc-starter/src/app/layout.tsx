import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Just Chat — Next.js 1-to-1 Audio & Video Starter",
  description: "Agora RTC Web SDK 4.x one-to-one audio and video starter with server-side token generation."
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
