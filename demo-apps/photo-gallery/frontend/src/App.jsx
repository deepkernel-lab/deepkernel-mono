import { useState, useEffect } from 'react';

const BACKEND_URL = import.meta.env.VITE_BACKEND_URL || 'http://localhost:8001';

function App() {
  const [photos, setPhotos] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetch(`${BACKEND_URL}/photos`)
      .then(res => {
        if (!res.ok) {
          throw new Error('Failed to fetch photos');
        }
        return res.json();
      })
      .then(setPhotos)
      .catch(err => setError(err.message));
  }, []);

  return (
    <div className="page">
      <header>
        <h1>Photo Gallery</h1>
        <p className="muted">A simple demo application</p>
      </header>
      <main>
        {error && <div className="error">{error}</div>}
        <div className="gallery">
          {photos.map(photo => (
            <div key={photo.id} className="card">
              <img src={photo.url} alt={photo.title} />
              <p>{photo.title}</p>
            </div>
          ))}
        </div>
      </main>
    </div>
  );
}

export default App;
