import { createDecipheriv } from 'node:crypto';

const baseUrl = 'https://cartoony.net';
const key = Buffer.from('7annaba3l_loves_crypto_safe_key!', 'utf8');
const headers = {
  'User-Agent': 'Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36',
  Accept: 'application/json, text/plain, */*',
  'Accept-Language': 'ar,en-US;q=0.9,en;q=0.8',
  Referer: `${baseUrl}/`,
  Origin: baseUrl,
};

function decodePayload(raw) {
  const envelope = JSON.parse(raw);
  if (!envelope || typeof envelope !== 'object' || !envelope.encryptedData || !envelope.iv) {
    return envelope;
  }

  const iv = Buffer.from(envelope.iv, 'hex');
  const encrypted = Buffer.from(envelope.encryptedData, 'hex');
  if (iv.length !== 16 || encrypted.length === 0 || key.length !== 32) {
    throw new Error('Cartoony returned a malformed encrypted API envelope');
  }
  const decipher = createDecipheriv('aes-256-cbc', key, iv);
  const plaintext = Buffer.concat([decipher.update(encrypted), decipher.final()]).toString('utf8');
  try {
    return JSON.parse(plaintext);
  } catch {
    return plaintext;
  }
}

async function request(path, options = {}) {
  const response = await fetch(new URL(path, baseUrl), {
    ...options,
    headers: { ...headers, ...options.headers },
    signal: AbortSignal.timeout(20_000),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`${path} returned HTTP ${response.status}`);
  try {
    return decodePayload(body);
  } catch (error) {
    throw new Error(`${path} response could not be decoded: ${error.message}`);
  }
}

function asArray(value, ...keys) {
  if (Array.isArray(value)) return value;
  if (!value || typeof value !== 'object') return [];
  for (const keyName of keys) {
    if (Array.isArray(value[keyName])) return value[keyName];
  }
  return [];
}

function isMovie(show) {
  return show?.is_movie === true || Number(show?.is_movie) === 1 || String(show?.is_movie).toLowerCase() === 'true';
}

function mediaUrls(value, output = [], depth = 0) {
  if (depth > 5 || value == null) return output;
  if (typeof value === 'string') {
    const candidate = value.trim().replaceAll('&amp;', '&');
    if (/^https?:\/\//i.test(candidate)) output.push(candidate);
    return output;
  }
  if (Array.isArray(value)) {
    for (const child of value) mediaUrls(child, output, depth + 1);
    return output;
  }
  if (typeof value === 'object') {
    for (const [name, child] of Object.entries(value)) {
      if (/^(?:link|url|stream|streamUrl|stream_url|video_url|file|direct_url|directUrl|download|downloadUrl|download_url|download_link|source_url|servers|sources|qualities|videos|links|data|result|episode|playback|payload)$/i.test(name)) {
        mediaUrls(child, output, depth + 1);
      }
    }
  }
  return output;
}

async function firstObjectUrl(url) {
  const response = await fetch(url, {
    headers: { ...headers, Accept: '*/*', Range: 'bytes=0-1023' },
    signal: AbortSignal.timeout(25_000),
  });
  if (!response.ok) throw new Error(`media URL returned HTTP ${response.status}`);
  const reader = response.body?.getReader();
  const firstChunk = reader ? await reader.read() : { value: new Uint8Array() };
  await reader?.cancel().catch(() => {});
  if (!firstChunk.value?.byteLength) throw new Error('media URL returned an empty response body');
  return { status: response.status, contentType: response.headers.get('content-type') ?? '' };
}

async function verifyHls(url, depth = 0) {
  if (depth > 2) throw new Error('HLS playlist nesting exceeded the test limit');
  const response = await fetch(url, {
    headers: { ...headers, Accept: '*/*' },
    signal: AbortSignal.timeout(25_000),
  });
  if (!response.ok) throw new Error(`HLS playlist returned HTTP ${response.status}`);
  const body = await response.text();
  if (!body.trimStart().startsWith('#EXTM3U')) throw new Error('media response is not an HLS playlist');

  const lines = body.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const child = lines.find((line) => !line.startsWith('#'));
  if (!child) throw new Error('HLS playlist has no variant or media segment');
  const childUrl = new URL(child, url).href;
  if (/\.m3u8(?:$|\?)/i.test(childUrl)) return verifyHls(childUrl, depth + 1);
  const segment = await firstObjectUrl(childUrl);
  return { playlistStatus: response.status, segmentStatus: segment.status, segmentType: segment.contentType };
}

const shows = asArray(await request('/api/sp/tvshows'), 'tvshows', 'shows', 'results', 'data');
if (shows.length === 0) throw new Error('The encrypted SP catalogue was empty');
console.log(`SP catalogue OK: ${shows.length} shows`);

const recent = asArray(await request('/api/sp/recentEpisodes'), 'episodes', 'results', 'data');
if (recent.length === 0) throw new Error('The encrypted recent-episodes feed was empty');
console.log(`Recent feed OK: ${recent.length} records`);

const movie = shows.find((item) => item && Number(item.id) > 0 && isMovie(item));
if (!movie) throw new Error('The SP catalogue contains no movie record to test');
const movieEpisodes = asArray(
  await request(`/api/sp/episodes?id=${encodeURIComponent(Number(movie.id))}`),
  'episodes', 'results', 'data',
);
if (movieEpisodes.length === 0) throw new Error(`Movie ${movie.id} has no episode/media record`);
console.log(`Movie detail OK: ${movie.name ?? movie.title ?? movie.id} (${movieEpisodes.length} media record(s))`);

let verified = null;
let checkedEpisodes = 0;
const series = shows
  .filter((item) => item && Number(item.id) > 0 && !isMovie(item))
  .sort((left, right) => Number(String(right.cost).toLowerCase() === 'free') - Number(String(left.cost).toLowerCase() === 'free'));
for (const show of series.slice(0, 10)) {
  const showId = Number(show.id);
  let episodes;
  try {
    episodes = asArray(await request(`/api/sp/episodes?id=${encodeURIComponent(showId)}`), 'episodes', 'results', 'data');
  } catch {
    continue;
  }
  for (const episode of episodes.slice(0, 3)) {
    const episodeId = Number(episode?.id ?? episode?.episode_id);
    if (!Number.isInteger(episodeId) || episodeId <= 0) continue;
    checkedEpisodes += 1;
    let playback;
    try {
      playback = await request('/api/sp/episode/link', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
        body: new URLSearchParams({ episodeId: String(episodeId), showId: String(showId) }),
      });
    } catch {
      continue;
    }
    const candidates = [...new Set(mediaUrls(playback))];
    const playable = candidates.find((candidate) => /\.m3u8(?:$|\?)/i.test(candidate) || /\/play_video\/index\.m3u8/i.test(candidate));
    if (!playable) continue;
    try {
      const media = await verifyHls(playable);
      verified = { showId, episodeId, media };
      break;
    } catch {
      // Try another current episode/server before reporting the live check as failed.
    }
  }
  if (verified) break;
}

if (!verified) {
  throw new Error(`No currently playable HLS stream was verified after checking ${checkedEpisodes} episodes`);
}
console.log(`Playback OK: show ${verified.showId}, episode ${verified.episodeId}; playlist HTTP ${verified.media.playlistStatus}; first media segment HTTP ${verified.media.segmentStatus}`);
