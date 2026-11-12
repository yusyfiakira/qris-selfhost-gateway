export interface TelegramConfig {
  botToken?: string;
  chatId?: string;
}

export function escapeTelegramHtml(text: unknown): string {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

export async function sendTelegramAlert(
  config: TelegramConfig,
  htmlMessage: string
): Promise<boolean> {
  const botToken = String(config.botToken || '').trim();
  const chatId = String(config.chatId || '').trim();
  if (!botToken || !chatId) return false;

  const url = `https://api.telegram.org/bot${botToken}/sendMessage`;
  const send = async (targetChatId: string) => {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        chat_id: targetChatId,
        text: htmlMessage,
        parse_mode: 'HTML',
        disable_web_page_preview: true,
      }),
    });
  };

  try {
    const res = await send(chatId);
    if (res.ok) return true;

    const body: any = await res.json().catch(() => null);
    const migratedId = body?.parameters?.migrate_to_chat_id;
    if (migratedId) {
      const retryRes = await send(String(migratedId));
      return retryRes.ok;
    }
    return false;
  } catch {
    return false;
  }
}
