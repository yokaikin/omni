import type { Metadata } from "next";
import { TicketFinderModalHost } from "@/components/ai/TicketFinderModalHost";
import { GlobalDialog } from "@/components/GlobalDialog";
import { MobileBottomNav } from "@/components/MobileBottomNav";
import { SupportFloatingButton } from "@/components/SupportFloatingButton";
import "./globals.css";

export const metadata: Metadata = {
  title: "万象-全球演出赛事官方购票平台-100%正品、先付先抢、在线选座！",
  description: "万象-全球演出赛事官方购票平台，100%正品，先付先抢，在线选座！",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="min-h-full flex flex-col bg-[#f5f5f5]">
        {children}
        <TicketFinderModalHost />
        <SupportFloatingButton />
        <MobileBottomNav />
        <GlobalDialog />
      </body>
    </html>
  );
}
