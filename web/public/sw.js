// Service worker do PWA (ADR 0033): guarda só a "casca" do site (HTML, scripts, estilos, ícones)
// para o app abrir mesmo sem internet. Nunca guarda nada de /api: dados de aluno, cobranças e
// tokens não ficam no aparelho. Sem rede, a tela abre e cada chamada à API mostra o erro de
// conexão.
const CACHE = 'casca-v1';

self.addEventListener('install', (evento) => {
  evento.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(['/', '/manifest.webmanifest'])));
  self.skipWaiting();
});

self.addEventListener('activate', (evento) => {
  evento.waitUntil((async () => {
    for (const nome of await caches.keys()) {
      if (nome !== CACHE) await caches.delete(nome);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (evento) => {
  const pedido = evento.request;
  const url = new URL(pedido.url);
  // Só GET do próprio site, e nunca a API nem o health.
  if (pedido.method !== 'GET' || url.origin !== self.location.origin
      || url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')) {
    return;
  }
  // Rede primeiro (versão nova assim que publicada); sem rede, o que está guardado. Uma
  // navegação (qualquer endereço do app) sem rede recebe a página inicial guardada.
  evento.respondWith((async () => {
    try {
      const resposta = await fetch(pedido);
      if (resposta.ok) {
        const cache = await caches.open(CACHE);
        await cache.put(pedido.mode === 'navigate' ? '/' : pedido, resposta.clone());
      }
      return resposta;
    } catch {
      const guardada = await caches.match(pedido.mode === 'navigate' ? '/' : pedido);
      return guardada ?? Response.error();
    }
  })());
});
