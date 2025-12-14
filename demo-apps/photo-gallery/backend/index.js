const express = require('express');
const cors = require('cors');

const app = express();
const port = 8001;

app.use(cors());

const photos = [
  { id: 1, title: 'A beautiful sunset', url: 'https://picsum.photos/id/10/400/300' },
  { id: 2, title: 'A forest path', url: 'https://picsum.photos/id/11/400/300' },
  { id: 3, title: 'A mountain landscape', url: 'https://picsum.photos/id/12/400/300' },
  { id: 4, title: 'City at night', url: 'https://picsum.photos/id/13/400/300' },
  { id: 5, title: 'A boat on the water', url: 'https://picsum.photos/id/14/400/300' },
  { id: 6, title: 'A bridge', url: 'https://picsum.photos/id/15/400/300' }
];

app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

app.get('/photos', (req, res) => {
  res.json(photos);
});

app.listen(port, () => {
  console.log(`Photo gallery backend listening at http://localhost:${port}`);
});
