import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Stockio | Product allocation",
  description: "Resolve a product against the Stockio catalog and allocate available inventory.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
