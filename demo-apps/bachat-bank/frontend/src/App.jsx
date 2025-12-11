import { useState, useEffect } from 'react';
import { login, getAccount, health } from './api';

function App() {
  const [token, setToken] = useState(null);
  const [account, setAccount] = useState(null);
  const [mode, setMode] = useState('unknown');
  const [error, setError] = useState(null);

  useEffect(() => {
    health()
      .then((h) => setMode(h.mode))
      .catch(() => setMode('unknown'));
  }, []);

  const handleLogin = async (e) => {
    e.preventDefault();
    const form = new FormData(e.target);
    const username = form.get('username');
    const password = form.get('password');
    try {
      const res = await login(username, password);
      setToken(res.token);
      setError(null);
      const acc = await getAccount(res.token);
      setAccount(acc);
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div className="page">
      <header>
        <h1>Bachat Bank</h1>
        <p className="muted">Demo app for DeepKernel runtime security</p>
        <div className="badge">Backend mode: {mode}</div>
      </header>
      <main>
        {!token && (
          <form className="card" onSubmit={handleLogin}>
            <h2>Login</h2>
            <label>
              Username
              <input name="username" defaultValue="demo" />
            </label>
            <label>
              Password
              <input name="password" type="password" defaultValue="demo" />
            </label>
            <button type="submit">Login</button>
            {error && <div className="error">{error}</div>}
          </form>
        )}
        {token && account && (
          <div className="card">
            <h2>Account Summary</h2>
            <div className="muted">User: {account.user}</div>
            <ul>
              {account.accounts.map((a) => (
                <li key={a.id}>
                  {a.id}: ${a.balance}
                </li>
              ))}
            </ul>
            <h3>Recent Transactions</h3>
            <ul>
              {account.transactions.map((t) => (
                <li key={t.id}>
                  {t.desc}: ${t.amount}
                </li>
              ))}
            </ul>
          </div>
        )}
      </main>
    </div>
  );
}

export default App;

