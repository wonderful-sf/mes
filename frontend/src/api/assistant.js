export async function requestMesAssistant(token, sessionId, action = '', payload, signal) {
  const response = await fetch('/api/assistant/sessions/' + sessionId + action, {
    method: payload === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: payload === undefined ? undefined : JSON.stringify(payload),
    signal
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result) {
    throw new Error(result?.error || '暂时无法连接 MES 助手，请稍后重试。');
  }
  return result;
}
