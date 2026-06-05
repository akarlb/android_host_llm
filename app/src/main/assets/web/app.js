(() => {
  const TOKEN_KEY = "phoneHostedAiToken";
  const state = {
    user: null,
    chats: [],
    currentChatId: null,
    messages: [],
    attachedFiles: [],
    skills: [],
    currentSkill: null,
    skillState: null,
    streaming: false,
    contextMessage: "",
    toolStatus: "",
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

  function showContextStatus(message) {
    const el = $("context-status");
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
    $("file-upload").addEventListener("change", uploadFile);
    $("skill-select").addEventListener("change", () => changeSkill($("skill-select").value));
    $("show-thinking-toggle").addEventListener("change", () => updateThinkingToggle($("show-thinking-toggle").checked));
    $("message-form").addEventListener("submit", sendMessage);
    $("message-input").addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey && !state.streaming) {
        event.preventDefault();
        $("message-form").requestSubmit();
      }
    });
    await loadSkills();
    await loadChats();
    if (!state.currentChatId) await createChat();
  }

  async function initAdmin() {
    const current = await session();
    if (!current.authenticated) {
      setToken("");
      $("admin-login").hidden = false;
      $("admin-logout-button").hidden = true;
      return;
    }
    state.user = current.user;
    $("admin-current-user").textContent = `${current.user.username} (${current.user.role})`;
    $("admin-logout-button").addEventListener("click", logout);
    $("admin-refresh-button").addEventListener("click", loadAdminDashboard);
    if (current.user.role !== "ADMIN") {
      $("admin-denied").hidden = false;
      return;
    }
    $("admin-dashboard").hidden = false;
    await loadAdminDashboard();
  }

  async function loadAdminDashboard() {
    showError("admin-error", "");
    try {
      const [status, users, files] = await Promise.all([
        jsonRequest("/api/admin/status"),
        jsonRequest("/api/admin/users"),
        jsonRequest("/api/admin/files"),
      ]);
      renderAdminStatus(status);
      renderAdminUrls(status);
      renderAdminUsers(users.users || []);
      renderAdminFiles(status, files.files || []);
      renderDiagnostics(status.debug || {});
    } catch (error) {
      if (error.message.includes("(401)")) $("admin-login").hidden = false;
      else if (error.message.includes("(403)") || error.message === "Forbidden") $("admin-denied").hidden = false;
      else showError("admin-error", error.message);
      $("admin-dashboard").hidden = true;
    }
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
    await loadSkills();
    await loadChats();
  }

  async function openChat(chatId) {
    state.currentChatId = chatId;
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(chatId)}`);
    state.messages = result.messages || [];
    state.attachedFiles = result.files || [];
    await loadChatSkill(chatId);
    renderChats();
    renderMessages();
    renderSelectedFiles();
    renderSkillControls();
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
    state.messages.forEach((message) => appendMessage(message.role, message.content, { final: true, thinking: message.thinking }));
    showContextStatus(state.contextMessage);
    list.scrollTop = list.scrollHeight;
  }

  function appendMessage(role, content, options = {}) {
    const el = document.createElement("article");
    el.className = `message ${role}`;
    el.setAttribute("aria-label", role === "user" ? "Your message" : "Assistant response");
    el.innerHTML = `<div class="thinking-slot"></div><div class="message-content"></div>`;
    const contentEl = el.querySelector(".message-content");
    renderThinking(el.querySelector(".thinking-slot"), role, options.thinking || "");
    setMessageContent(contentEl, role, content || "", options.final === true);
    $("messages").appendChild(el);
    $("messages").scrollTop = $("messages").scrollHeight;
    return contentEl;
  }

  function renderThinking(slot, role, thinking) {
    if (role !== "assistant" || !thinking || !state.skillState || !state.skillState.showThinking) {
      slot.innerHTML = "";
      return;
    }
    slot.innerHTML = `<details class="thinking-panel"><summary>Model reasoning</summary><div class="thinking-body">${escapeText(thinking)}</div></details>`;
  }

  function setMessageContent(contentEl, role, content, final) {
    if (role === "assistant" && final) {
      contentEl.innerHTML = renderMarkdown(content);
    } else {
      contentEl.textContent = content || "";
    }
  }

  function showTypingIndicator(contentEl) {
    contentEl.innerHTML = `<span class="typing-indicator" aria-label="Generating response"><span></span><span></span><span></span></span>`;
    contentEl.dataset.typing = "true";
  }

  function clearTypingIndicator(contentEl) {
    if (contentEl.dataset.typing === "true") {
      contentEl.textContent = "";
      delete contentEl.dataset.typing;
    }
  }


  async function loadSkills() {
    const result = await jsonRequest("/api/skills");
    state.skills = result.skills || [];
    renderSkillControls();
  }

  async function loadChatSkill(chatId) {
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(chatId)}/skill`);
    state.currentSkill = result.skill;
    state.skillState = result.state;
  }

  function renderSkillControls() {
    const select = $("skill-select");
    if (!select) return;
    select.innerHTML = "";
    state.skills.forEach((skill) => {
      const option = document.createElement("option");
      option.value = skill.slug;
      option.textContent = skill.displayName;
      if (state.currentSkill && state.currentSkill.slug === skill.slug) option.selected = true;
      select.appendChild(option);
    });
    const toggle = $("show-thinking-toggle");
    if (toggle) toggle.checked = Boolean(state.skillState && state.skillState.showThinking);
  }

  async function changeSkill(slug, options = {}) {
    if (!state.currentChatId) return;
    const body = { skillSlug: slug };
    if (Object.prototype.hasOwnProperty.call(options, "thinkingEnabled")) body.thinkingEnabled = options.thinkingEnabled;
    if (Object.prototype.hasOwnProperty.call(options, "showThinking")) body.showThinking = options.showThinking;
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(state.currentChatId)}/skill`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
    state.currentSkill = result.skill;
    state.skillState = result.state;
    renderSkillControls();
    renderMessages();
    showSkillStatus(`Skill changed to ${result.skill.displayName}`);
  }

  async function updateThinkingToggle(showThinking) {
    if (!state.currentChatId || !state.currentSkill) return;
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(state.currentChatId)}/skill`, {
      method: "PUT",
      body: JSON.stringify({ skillSlug: state.currentSkill.slug, showThinking }),
    });
    state.currentSkill = result.skill;
    state.skillState = result.state;
    renderMessages();
  }

  function showSkillStatus(message) {
    const el = $("skill-status");
    if (!el) return;
    el.textContent = message || "";
    if (message) setTimeout(() => { if (el.textContent === message) el.textContent = ""; }, 2500);
  }

  function slashCommand(content) {
    if (!content.startsWith("/")) return null;
    const [command, ...rest] = content.split(/\s+/);
    const aliases = {
      "/default": "default",
      "/coding": "coding",
      "/code": "coding",
      "/gdpr": "gdpr-pii-audit",
      "/pii": "gdpr-pii-audit",
      "/markdown": "markdown-qa",
      "/md": "markdown-qa",
      "/qa": "markdown-qa",
    };
    return { slug: aliases[command.toLowerCase()] || null, trailing: rest.join(" ").trim(), command };
  }

  async function sendMessage(event) {
    event.preventDefault();
    if (state.streaming) return;
    const input = $("message-input");
    let content = input.value.trim();
    if (!content || !state.currentChatId) return;
    showError("chat-error", "");
    const command = slashCommand(content);
    if (command) {
      if (!command.slug) {
        showError("chat-error", `Unknown slash command: ${command.command}`);
        return;
      }
      await changeSkill(command.slug);
      if (!command.trailing) {
        input.value = "";
        return;
      }
      content = command.trailing;
    }
    state.streaming = true;
    $("send-button").disabled = true;
    input.setAttribute("aria-busy", "true");
    input.value = "";
    appendMessage("user", content, { final: true });
    const assistantContent = appendMessage("assistant", "");
    showTypingIndicator(assistantContent);
    try {
      await streamMessage(content, assistantContent);
      await loadSkills();
    await loadChats();
    } catch (error) {
      clearTypingIndicator(assistantContent);
      showError("chat-error", error.message);
      assistantContent.textContent = error.message;
    } finally {
      state.streaming = false;
      $("send-button").disabled = false;
      input.removeAttribute("aria-busy");
    }
  }

  async function streamMessage(content, assistantContent) {
    const response = await fetch(`/api/chats/${encodeURIComponent(state.currentChatId)}/messages`, {
      method: "POST",
      headers: headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        content,
        stream: true,
        fileIds: state.attachedFiles.map((file) => file.id),
        skillSlug: state.currentSkill ? state.currentSkill.slug : undefined,
        thinkingEnabled: state.skillState ? state.skillState.thinkingEnabled : undefined,
        showThinking: state.skillState ? state.skillState.showThinking : false,
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
            clearTypingIndicator(assistantContent);
            const finalContent = finalMessage ? finalMessage.content || assistantContent.textContent : assistantContent.textContent;
            if (finalMessage) {
              const article = assistantContent.closest(".message");
              if (article) renderThinking(article.querySelector(".thinking-slot"), "assistant", finalMessage.thinking || "");
            }
            setMessageContent(assistantContent, "assistant", finalContent, true);
            const toolEl = $("tool-status");
            if (toolEl) setTimeout(() => { toolEl.textContent = ""; }, 2000);
            return;
          }
          const parsed = JSON.parse(payload);
          if (parsed.error) throw new Error(parsed.error.message || parsed.error);
          if (parsed.skill) {
            state.currentSkill = parsed.skill;
            renderSkillControls();
          }
          if (parsed.toolCall) {
            const status = parsed.toolCall.status === "started" ? `Using tool: ${parsed.toolCall.name}...` : `Tool ${parsed.toolCall.status}.`;
            const el = $("tool-status");
            if (el) el.textContent = status;
          }
          if (parsed.thinking && parsed.thinking.visible && parsed.thinking.content) {
            const article = assistantContent.closest(".message");
            if (article) renderThinking(article.querySelector(".thinking-slot"), "assistant", parsed.thinking.content);
          }
          if (parsed.context) {
            state.contextMessage = parsed.context.message || "";
            showContextStatus(state.contextMessage);
          }
          if (parsed.content) {
            clearTypingIndicator(assistantContent);
            assistantContent.textContent += parsed.content;
          }
          if (parsed.message) finalMessage = parsed.message;
          $("messages").scrollTop = $("messages").scrollHeight;
        }
      }
    }
  }

  async function loadChatFiles() {
    if (!state.currentChatId) {
      state.attachedFiles = [];
      renderSelectedFiles();
      return;
    }
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(state.currentChatId)}/files`);
    state.attachedFiles = result.files || [];
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
      if (!state.currentChatId) await createChat();
      const content = await file.text();
      const result = await jsonRequest("/api/files/upload", {
        method: "POST",
        body: JSON.stringify({ filename: file.name, mimeType: file.type || "text/markdown", content }),
      });
      await jsonRequest(`/api/chats/${encodeURIComponent(state.currentChatId)}/files`, {
        method: "POST",
        body: JSON.stringify({ fileId: result.file.id }),
      });
      $("file-upload").value = "";
      await loadChatFiles();
    } catch (error) {
      showError("files-error", error.message);
    } finally {
      $("file-upload").value = "";
    }
  }

  function renderSelectedFiles() {
    const holder = $("selected-files");
    holder.innerHTML = "";
    state.attachedFiles.forEach((file) => {
      const chip = document.createElement("div");
      chip.className = "file-chip";
      const name = document.createElement("span");
      name.innerHTML = `<strong>${escapeText(file.originalFilename || file.filename)}</strong> <span>${formatBytes(file.sizeBytes)} - ${file.chunkCount || 0} chunks</span>`;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "file-chip-remove";
      remove.setAttribute("aria-label", `Detach ${file.originalFilename || file.filename}`);
      remove.textContent = "x";
      remove.addEventListener("click", () => detachChatFile(file.id).catch((error) => showError("files-error", error.message)));
      chip.append(name, remove);
      holder.appendChild(chip);
    });
  }

  async function detachChatFile(fileId) {
    if (!state.currentChatId) return;
    await jsonRequest(`/api/chats/${encodeURIComponent(state.currentChatId)}/files/${encodeURIComponent(fileId)}`, { method: "DELETE" });
    state.attachedFiles = state.attachedFiles.filter((file) => file.id !== fileId);
    renderSelectedFiles();
  }

  function formatBytes(value) {
    const bytes = Number(value || 0);
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  function renderMarkdown(markdown) {
    const lines = String(markdown || "").replace(/\r\n/g, "\n").split("\n");
    const html = [];
    let paragraph = [];
    let inCodeBlock = false;
    let codeLanguage = "";
    let codeLines = [];
    let listType = null;

    const closeParagraph = () => {
      if (!paragraph.length) return;
      html.push(`<p>${formatInlineMarkdown(paragraph.join("\n"))}</p>`);
      paragraph = [];
    };
    const closeList = () => {
      if (!listType) return;
      html.push(`</${listType}>`);
      listType = null;
    };
    const closeCodeBlock = () => {
      html.push(`<pre><code${codeLanguage ? ` class="language-${escapeText(codeLanguage)}"` : ""}>${escapeText(codeLines.join("\n"))}</code></pre>`);
      inCodeBlock = false;
      codeLanguage = "";
      codeLines = [];
    };

    lines.forEach((line) => {
      const fence = line.match(/^```([A-Za-z0-9_-]+)?\s*$/);
      if (fence) {
        if (inCodeBlock) {
          closeCodeBlock();
        } else {
          closeParagraph();
          closeList();
          inCodeBlock = true;
          codeLanguage = fence[1] || "";
          codeLines = [];
        }
        return;
      }
      if (inCodeBlock) {
        codeLines.push(line);
        return;
      }

      const heading = line.match(/^(#{1,6})\s+(.+)$/);
      if (heading) {
        closeParagraph();
        closeList();
        const level = heading[1].length;
        html.push(`<h${level}>${formatInlineMarkdown(heading[2].trim())}</h${level}>`);
        return;
      }

      const unordered = line.match(/^\s*[-*]\s+(.+)$/);
      const ordered = line.match(/^\s*\d+\.\s+(.+)$/);
      if (unordered || ordered) {
        closeParagraph();
        const nextType = unordered ? "ul" : "ol";
        if (listType !== nextType) {
          closeList();
          html.push(`<${nextType}>`);
          listType = nextType;
        }
        html.push(`<li>${formatInlineMarkdown((unordered || ordered)[1].trim())}</li>`);
        return;
      }

      if (!line.trim()) {
        closeParagraph();
        closeList();
        return;
      }
      paragraph.push(line);
    });

    if (inCodeBlock) closeCodeBlock();
    closeParagraph();
    closeList();
    return html.join("");
  }

  function formatInlineMarkdown(text) {
    return String(text || "")
      .split(/(`[^`]+`)/g)
      .map((part) => {
        if (part.startsWith("`") && part.endsWith("`")) {
          return `<code>${escapeText(part.slice(1, -1))}</code>`;
        }
        return formatInlineWithoutCode(part);
      })
      .join("");
  }

  function formatInlineWithoutCode(text) {
    let html = escapeText(text);
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_, label, url) => {
      const safeUrl = sanitizeMarkdownUrl(url);
      if (!safeUrl) return label;
      return `<a href="${safeUrl}" target="_blank" rel="noopener noreferrer">${label}</a>`;
    });
    html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    html = html.replace(/(^|[\s(])_([^_\n]+)_/g, "$1<em>$2</em>");
    return html.replace(/\n/g, "<br>");
  }

  function sanitizeMarkdownUrl(value) {
    const decoded = String(value || "")
      .replace(/&amp;/g, "&")
      .replace(/&quot;/g, "\"")
      .replace(/&#39;/g, "'");
    const trimmed = decoded.trim();
    if (!trimmed) return null;
    const lower = trimmed.replace(/[\u0000-\u001f\s]+/g, "").toLowerCase();
    if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) return null;
    if (
      lower.startsWith("https://") ||
      lower.startsWith("http://") ||
      lower.startsWith("mailto:") ||
      trimmed.startsWith("/") ||
      trimmed.startsWith("#")
    ) {
      return escapeText(trimmed);
    }
    return null;
  }

  function renderAdminStatus(status) {
    const speculative = status.speculativeDecodingEnabled
      ? "enabled"
      : status.speculativeDecodingRequested
        ? "requested"
        : "disabled";
    renderDefinitionList("system-status", [
      ["Model loaded", status.modelLoaded ? "Yes" : "No"],
      ["Backend", status.backendStatus || "Unknown"],
      ["Server mode", status.serverMode || "Unknown"],
      ["LAN IP", status.lanIp || "Unavailable"],
      ["MTP/speculative decoding", speculative],
      ["Active generation", status.activeGeneration ? "Yes" : "No"],
    ]);
  }

  function renderAdminUrls(status) {
    const urls = [
      ["Normal web app", status.normalWebUrl],
      ["Coding client base URL", status.codingBaseUrl],
      ["Conversation client base URL", status.conversationBaseUrl],
      ["Compatibility base URL", status.compatibilityBaseUrl || `${window.location.origin}/v1`],
    ];
    const list = $("admin-urls");
    list.innerHTML = "";
    urls.forEach(([label, url]) => {
      const row = document.createElement("div");
      row.className = "url-row";
      row.innerHTML = `<span>${escapeText(label)}</span><code>${escapeText(url || "")}</code>`;
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = "Copy";
      button.addEventListener("click", () => copyText(url || ""));
      row.appendChild(button);
      list.appendChild(row);
    });
  }

  function renderAdminUsers(users) {
    const body = $("admin-users");
    body.innerHTML = "";
    users.forEach((user) => {
      const row = document.createElement("tr");
      row.innerHTML = `
        <td>${escapeText(user.username)}</td>
        <td>${escapeText(user.role)}</td>
        <td>${escapeText(formatDate(user.createdAtMs))}</td>
        <td>${Number(user.chatCount || 0)}</td>
        <td>${Number(user.fileCount || 0)}</td>
      `;
      body.appendChild(row);
    });
  }

  function renderAdminFiles(status, files) {
    renderDefinitionList("storage-status", [
      ["Total uploaded files", Number(status.totalFiles || files.length).toString()],
      ["Approximate storage", formatBytes(status.totalStorageBytes || files.reduce((sum, file) => sum + Number(file.sizeBytes || 0), 0))],
    ]);
    const list = $("admin-files");
    list.innerHTML = "";
    files.slice(0, 12).forEach((file) => {
      const item = document.createElement("div");
      item.className = "recent-file";
      item.innerHTML = `
        <strong>${escapeText(file.filename)}</strong>
        <span class="file-meta">${escapeText(file.username)} - ${formatBytes(file.sizeBytes)} - ${Number(file.chunkCount || 0)} chunks - ${escapeText(formatDate(file.createdAtMs))}</span>
      `;
      list.appendChild(item);
    });
  }

  function renderDiagnostics(debug) {
    const links = [
      debug.perf || "/debug/perf",
      debug.perfHistory || "/debug/perf/history",
      debug.config || "/debug/config",
      debug.routes || "/debug/routes",
      debug.health || "/health",
    ];
    const holder = $("admin-diagnostics");
    holder.innerHTML = "";
    links.forEach((href) => {
      const link = document.createElement("a");
      link.href = href;
      link.textContent = href;
      holder.appendChild(link);
    });
  }

  function renderDefinitionList(id, entries) {
    const list = $(id);
    list.innerHTML = "";
    entries.forEach(([label, value]) => {
      const term = document.createElement("dt");
      const detail = document.createElement("dd");
      term.textContent = label;
      detail.textContent = value;
      list.append(term, detail);
    });
  }

  function formatDate(ms) {
    const value = Number(ms || 0);
    if (!value) return "Unknown";
    return new Date(value).toLocaleString();
  }

  async function copyText(value) {
    if (!value) return;
    await navigator.clipboard.writeText(value);
  }

  document.addEventListener("DOMContentLoaded", () => {
    if (page === "index") initIndex().catch(() => { window.location.href = "/login"; });
    if (page === "login") initLogin();
    if (page === "register") initRegister();
    if (page === "chat") initChat().catch((error) => showError("chat-error", error.message));
    if (page === "admin") initAdmin().catch((error) => showError("admin-error", error.message));
  });
})();
