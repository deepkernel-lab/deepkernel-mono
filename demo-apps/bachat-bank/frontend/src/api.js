const BACKEND_URL = import.meta.env.VITE_BACKEND_URL || 'http://localhost:8000';

export async function login(username, password) {
  const res = await fetch(`${BACKEND_URL}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error('login failed');
  return res.json();
}

export async function getAccount(token) {
  const res = await fetch(`${BACKEND_URL}/account`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error('account failed');
  return res.json();
}

export async function health() {
  const res = await fetch(`${BACKEND_URL}/health`);
  if (!res.ok) throw new Error('health failed');
  return res.json();
}

