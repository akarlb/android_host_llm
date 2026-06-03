(() => {
  const TOKEN_KEY = "phoneHostedAiToken";
  const state = {
    user: null,
    chats: [],
    currentChatId: null,
    messages: [],
    files: [],
    selectedFileIds: new Set(),
    streaming: false,
  };

  const $ = (id) => document.getElementById(id);
  const page = document.body.dataset.page;

  function token() {
    return localStorage.getItem(TOKEN_KEY) || "";
  }

  function setToken(value) {
    if (value) localStorage.setItem(TOKEN_KEY, value);
    else localStorage.removeItem(TOKEN_KEY);
  }

  function headers(extra = {}) {
    const auth = token();
    return {
      ...extra,
      ...(auth ? { Authorization: `Bearer ${auth}` } : {}),
    };
  }

  async function jsonRequest(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: headers({
        "Content-Type": "application/json",
        ...(options.headers || {}),
      }),
    });
    const text = await response.text();
    const body = text ? JSON.parse(text) : {};
    if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
    return body;
  }

  async function session() {
    const response = await fetch("/auth/session", { headers: headers() });
    return response.json();
  }

  function showError(id, message) {
    const el = $(id);
    if (el) el.textContent = message || "";
  }

  function requireInput(id) {
    return $(id).value.trim();
  }

  function escapeText(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  async function initIndex() {
    const current = await session();
    window.location.href = current.authenticated ? "/chat" : "/login";
  }

  function initLogin() {
    $("login-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      showError("login-error", "");
      try {
        const body = {
          username: requireInput("login-username"),
          password: $("login-password").value,
        };
        const result = await jsonRequest("/auth/login", { method: "POST", body: JSON.stringify(body) });
        setToken(result.token);
        window.location.href = "/chat";
      } catch (error) {
        showError("login-error", error.message);
      }
    });
  }

  function initRegister() {
    $("register-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      showError("register-error", "");
      const password = $("register-password").value;
      if (password !== $("register-confirm").value) {
        showError("register-error", "Passwords do not match.");
        return;
      }
      try {
        const body = { username: requireInput("register-username"), password };
        const result = await jsonRequest("/auth/register", { method: "POST", body: JSON.stringify(body) });
        setToken(result.token);
        window.location.href = "/chat";
      } catch (error) {
        showError("register-error", error.message);
      }
    });
  }

  async function initChat() {
    const current = await session();
    if (!current.authenticated) {
      setToken("");
      window.location.href = "/login";
      return;
    }
    state.user = current.user;
    $("current-user").textContent = `${current.user.username} (${current.user.role})`;
    $("admin-link").hidden = current.user.role !== "ADMIN";
    $("logout-button").addEventListener("click", logout);
    $("new-chat-button").addEventListener("click", createChat);
    $("refresh-files-button").addEventListener("click", loadFiles);
    $("file-upload").addEventListener("change", uploadFile);
    $("message-form").addEventListener("submit", sendMessage);
    await Promise.all([loadChats(), loadFiles()]);
    if (!state.currentChatId) await createChat();
  }

  async function logout() {
    try {
      await jsonRequest("/auth/logout", { method: "POST", body: "{}" });
    } catch (_) {
      // Token is cleared locally even if the server-side session already expired.
    }
    setToken("");
    window.location.href = "/login";
  }

  async function loadChats() {
    const result = await jsonRequest("/api/chats");
    state.chats = result.chats || [];
    if (!state.currentChatId && state.chats.length) state.currentChatId = state.chats[0].id;
    renderChats();
    if (state.currentChatId) await openChat(state.currentChatId);
  }

  async function createChat() {
    const result = await jsonRequest("/api/chats", {
      method: "POST",
      body: JSON.stringify({ title: "New chat", profile: "CONVERSATION" }),
    });
    state.currentChatId = result.chat.id;
    state.messages = [];
    await loadChats();
  }

  async function openChat(chatId) {
    state.currentChatId = chatId;
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(chatId)}`);
    state.messages = result.messages || [];
    renderChats();
    renderMessages();
  }

  function renderChats() {
    const list = $("chat-list");
    list.innerHTML = "";
    state.chats.forEach((chat) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `chat-item${chat.id === state.currentChatId ? " active" : ""}`;
      button.innerHTML = `<strong>${escapeText(chat.title || "Chat")}</strong><span class="file-meta">${escapeText(chat.profile)}</span>`;
      button.addEventListener("click", () => openChat(chat.id));
      list.appendChild(button);
    });
  }

  function renderMessages() {
    const list = $("messages");
    list.innerHTML = "";
    state.messages.forEach((message) => appendMessage(message.role, message.content));
    list.scrollTop = list.scrollHeight;
  }

  function appendMessage(role, content) {
    const el = document.createElement("article");
    el.className = `message ${role}`;
    el.innerHTML = `<div class="message-role">${escapeText(role)}</div><div class="message-content"></div>`;
    el.querySelector(".message-content").textContent = content || "";
    $("messages").appendChild(el);
    $("messages").scrollTop = $("messages").scrollHeight;
    return el.querySelector(".message-content");
  }

  async function sendMessage(event) {
    event.preventDefault();
    if (state.streaming) return;
    const input = $("message-input");
    const content = input.value.trim();
    if (!content || !state.currentChatId) return;
    showError("chat-error", "");
    state.streaming = true;
    $("send-button").disabled = true;
    input.value = "";
    appendMessage("user", content);
    const assistantContent = appendMessage("assistant", "");
    try {
      await streamMessage(content, assistantContent);
      await loadChats();
    } catch (error) {
      showError("chat-error", error.message);
    } finally {
      state.streaming = false;
      $("send-button").disabled = false;
    }
  }

  async function streamMessage(content, assistantContent) {
    const response = await fetch(`/api/chats/${encodeURIComponent(state.currentChatId)}/messages`, {
      method: "POST",
      headers: headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        content,
        stream: true,
        fileIds: Array.from(state.selectedFileIds),
      }),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || `Message failed (${response.status})`);
    }
    if (!response.body) throw new Error("Streaming is not available in this browser.");

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let finalMessage = null;
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split("\n\n");
      buffer = events.pop() || "";
      for (const event of events) {
        const lines = event.split("\n").filter((line) => line.startsWith("data:"));
        for (const line of lines) {
          const payload = line.replace(/^data:\s?/, "");
          if (payload === "[DONE]") {
            if (finalMessage) assistantContent.textContent = finalMessage.content || assistantContent.textContent;
            return;
          }
          const parsed = JSON.parse(payload);
          if (parsed.error) throw new Error(parsed.error.message || parsed.error);
          if (parsed.content) assistantContent.textContent += parsed.content;
          if (parsed.message) finalMessage = parsed.message;
          $("messages").scrollTop = $("messages").scrollHeight;
        }
      }
    }
  }

  async function loadFiles() {
    const result = await jsonRequest("/api/files");
    state.files = result.files || [];
    for (const id of Array.from(state.selectedFileIds)) {
      if (!state.files.some((file) => file.id === id)) state.selectedFileIds.delete(id);
    }
    renderFiles();
    renderSelectedFiles();
  }

  async function uploadFile() {
    showError("files-error", "");
    const file = $("file-upload").files[0];
    if (!file) return;
    if (!file.name.toLowerCase().endsWith(".md")) {
      showError("files-error", "Only .md Markdown files are accepted.");
      $("file-upload").value = "";
      return;
    }
    try {
      const content = await file.text();
      await jsonRequest("/api/files/upload", {
        method: "POST",
        body: JSON.stringify({ filename: file.name, mimeType: file.type || "text/markdown", content }),
      });
      $("file-upload").value = "";
      await loadFiles();
    } catch (error) {
      showError("files-error", error.message);
    }
  }

  async function deleteFile(fileId) {
    await jsonRequest(`/api/files/${encodeURIComponent(fileId)}`, { method: "DELETE" });
    state.selectedFileIds.delete(fileId);
    await loadFiles();
  }

  function renderFiles() {
    const list = $("file-list");
    list.innerHTML = "";
    state.files.forEach((file) => {
      const item = document.createElement("label");
      item.className = "file-item";
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = state.selectedFileIds.has(file.id);
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) state.selectedFileIds.add(file.id);
        else state.selectedFileIds.delete(file.id);
        renderSelectedFiles();
      });
      const detail = document.createElement("span");
      detail.innerHTML = `<strong>${escapeText(file.originalFilename || file.filename)}</strong><span class="file-meta">${formatBytes(file.sizeBytes)} - ${file.chunkCount || 0} chunks</span>`;
      const del = document.createElement("button");
      del.type = "button";
      del.className = "file-delete";
      del.textContent = "Delete";
      del.addEventListener("click", (event) => {
        event.preventDefault();
        deleteFile(file.id).catch((error) => showError("files-error", error.message));
      });
      item.append(checkbox, detail, del);
      list.appendChild(item);
    });
  }

  function renderSelectedFiles() {
    const holder = $("selected-files");
    holder.innerHTML = "";
    Array.from(state.selectedFileIds).forEach((id) => {
      const file = state.files.find((candidate) => candidate.id === id);
      if (!file) return;
      const chip = document.createElement("span");
      chip.className = "file-chip";
      chip.textContent = file.originalFilename || file.filename;
      holder.appendChild(chip);
    });
  }

  function formatBytes(value) {
    const bytes = Number(value || 0);
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  document.addEventListener("DOMContentLoaded", () => {
    if (page === "index") initIndex().catch(() => { window.location.href = "/login"; });
    if (page === "login") initLogin();
    if (page === "register") initRegister();
    if (page === "chat") initChat().catch((error) => showError("chat-error", error.message));
  });
})();
