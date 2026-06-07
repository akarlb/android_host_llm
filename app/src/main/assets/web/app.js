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
    adminSkills: [],
    adminTools: [],
    adminToolLogs: [],
    adminGenerations: [],
    adminGenerationSummary: null,
    selectedAdminSkill: null,
    currentGenerationId: null,
    currentChatGeneration: null,
    chatFilter: "",
    streaming: false,
    contextMessage: "",
    toolStatus: "",
    skillsLoadError: "",
    slashMenuItems: [],
    slashMenuIndex: 0,
  };

  const SKILL_ALIASES = {
    default: "default",
    coding: "coding",
    code: "coding",
    gdpr: "gdpr-pii-audit",
    pii: "gdpr-pii-audit",
    markdown: "markdown-qa",
    md: "markdown-qa",
    qa: "markdown-qa",
  };

  const PREFERRED_SKILL_COMMANDS = {
    default: "default",
    coding: "coding",
    "gdpr-pii-audit": "gdpr",
    "markdown-qa": "markdown",
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
    if (!response.ok) {
      const error = new Error(body.error || `Request failed (${response.status})`);
      error.status = response.status;
      error.code = body.errorDetails ? body.errorDetails.code : body.code;
      error.details = body.errorDetails ? body.errorDetails.details : body.details;
      throw error;
    }
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
    $("chat-search").addEventListener("input", () => {
      state.chatFilter = $("chat-search").value.trim().toLowerCase();
      renderChats();
    });
    $("file-upload").addEventListener("change", uploadFile);
    $("show-thinking-toggle").addEventListener("change", () => updateThinkingToggle($("show-thinking-toggle").checked));
    $("message-form").addEventListener("submit", sendMessage);
    $("stop-button").addEventListener("click", stopGeneration);
    $("retry-button").addEventListener("click", retryGeneration);
    const messageInput = $("message-input");
    messageInput.addEventListener("input", updateSlashMenu);
    messageInput.addEventListener("blur", () => setTimeout(hideSlashMenu, 120));
    messageInput.addEventListener("keydown", (event) => {
      if (handleSlashMenuKeydown(event)) return;
      if (event.key === "Enter" && !event.shiftKey && !state.streaming) {
        event.preventDefault();
        $("message-form").requestSubmit();
      }
    });
    await loadSkills();
    await loadModelStatus();
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
    $("skill-new-button").addEventListener("click", () => editAdminSkill(null));
    $("skill-form").addEventListener("submit", saveAdminSkill);
    $("skill-delete-button").addEventListener("click", deleteAdminSkill);
    $("skill-export-button").addEventListener("click", exportAdminSkills);
    $("skill-import-button").addEventListener("click", importAdminSkills);
    $("skill-test-form").addEventListener("submit", runSkillTest);
    $("ops-export-button").addEventListener("click", downloadBackup);
    $("ops-diagnostics-button").addEventListener("click", downloadDiagnostics);
    $("ops-scan-button").addEventListener("click", scanStorage);
    $("ops-cleanup-button").addEventListener("click", cleanupStorage);
    $("generations-refresh-button").addEventListener("click", loadAdminGenerations);
    $("generations-cancel-all-button").addEventListener("click", cancelAllGenerations);
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
      const [status, users, files, skills, tools, logs, generations] = await Promise.all([
        jsonRequest("/api/admin/status"),
        jsonRequest("/api/admin/users"),
        jsonRequest("/api/admin/files"),
        jsonRequest("/api/admin/skills"),
        jsonRequest("/api/admin/tools"),
        jsonRequest("/api/admin/tools/logs"),
        jsonRequest("/api/admin/generations"),
      ]);
      state.adminSkills = skills.skills || [];
      state.adminTools = tools.tools || [];
      state.adminToolLogs = logs.logs || [];
      state.adminGenerations = generations.generations || [];
      state.adminGenerationSummary = generations.generation || status.generation || null;
      renderAdminStatus(status);
      renderAdminGenerations();
      renderAdminUrls(status);
      renderAdminUsers(users.users || []);
      renderAdminFiles(status, files.files || []);
      renderAdminSkills();
      renderAdminTools();
      renderToolMatrix();
      renderToolLogs();
      renderSkillTestOptions();
      if (!state.selectedAdminSkill) editAdminSkill(state.adminSkills.find((skill) => !skill.builtIn) || null);
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
    await loadChatGenerations(chatId);
    renderChats();
    renderMessages();
    renderSelectedFiles();
    renderSkillControls();
  }

  async function loadChatGenerations(chatId = state.currentChatId) {
    if (!chatId) return;
    try {
      const result = await jsonRequest(`/api/chats/${encodeURIComponent(chatId)}/generations`);
      state.currentChatGeneration = result.activeGeneration || null;
      renderChatGenerationStatus();
    } catch (_) {
      state.currentChatGeneration = null;
      renderChatGenerationStatus();
    }
  }

  function renderChatGenerationStatus() {
    const holder = $("chat-generation-status");
    if (!holder) return;
    const job = state.currentChatGeneration;
    if (!job || !job.isActive) {
      holder.hidden = true;
      holder.innerHTML = "";
      return;
    }
    holder.hidden = false;
    holder.innerHTML = `
      <div>
        <strong>Previous generation still active</strong>
        <span>${escapeText(job.status || "active")} - ${formatDuration(job.activeAgeMs || job.ageMs)}</span>
      </div>
      <div class="button-row tight">
        <button type="button" data-action="cancel">Cancel</button>
        <button type="button" data-action="refresh">Refresh</button>
      </div>
    `;
    holder.querySelector('[data-action="cancel"]').addEventListener("click", stopGeneration);
    holder.querySelector('[data-action="refresh"]').addEventListener("click", () => loadChatGenerations());
  }

  function renderChats() {
    const list = $("chat-list");
    list.innerHTML = "";
    const chats = state.chats.filter((chat) => !state.chatFilter || `${chat.title} ${chat.profile}`.toLowerCase().includes(state.chatFilter));
    if (!chats.length) {
      const empty = document.createElement("p");
      empty.className = "empty-state";
      empty.textContent = state.chatFilter ? "No chats match this search." : "No chats yet.";
      list.appendChild(empty);
      return;
    }
    chats.forEach((chat) => {
      const item = document.createElement("div");
      item.className = `chat-item${chat.id === state.currentChatId ? " active" : ""}`;
      item.innerHTML = `
        <button type="button" class="chat-open">
          <strong>${escapeText(chat.title || "Chat")}</strong>
          <span class="file-meta">${escapeText(chat.profile)} - ${escapeText(formatDate(chat.updatedAtMs))}</span>
        </button>
        <div class="chat-actions">
          <button type="button" data-action="rename">Rename</button>
          <button type="button" data-action="archive">Archive</button>
        </div>
      `;
      item.querySelector(".chat-open").addEventListener("click", () => openChat(chat.id));
      item.querySelector('[data-action="rename"]').addEventListener("click", () => renameChat(chat));
      item.querySelector('[data-action="archive"]').addEventListener("click", () => archiveChat(chat));
      list.appendChild(item);
    });
  }

  async function renameChat(chat) {
    const title = window.prompt("Rename chat", chat.title || "New chat");
    if (title == null) return;
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(chat.id)}`, {
      method: "PUT",
      body: JSON.stringify({ title }),
    });
    state.chats = state.chats.map((item) => item.id === chat.id ? result.chat : item);
    renderChats();
  }

  async function archiveChat(chat) {
    if (!window.confirm(`Archive "${chat.title || "this chat"}"?`)) return;
    await jsonRequest(`/api/chats/${encodeURIComponent(chat.id)}`, { method: "DELETE" });
    if (state.currentChatId === chat.id) {
      state.currentChatId = null;
      state.messages = [];
      $("messages").innerHTML = "";
    }
    await loadChats();
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
    el.innerHTML = `<div class="message-toolbar"><span>${role === "user" ? "You" : "Assistant"}</span><button type="button">Copy</button></div><div class="thinking-slot"></div><div class="message-content"></div>`;
    const contentEl = el.querySelector(".message-content");
    el.querySelector(".message-toolbar button").addEventListener("click", () => copyText(content || contentEl.textContent || ""));
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
    try {
      const result = await jsonRequest("/api/skills");
      state.skills = result.skills || [];
      state.skillsLoadError = "";
    } catch (error) {
      state.skills = [];
      state.skillsLoadError = error.message || "Skills failed to load.";
      showError("chat-error", `Skills unavailable: ${state.skillsLoadError}. Normal chat still works.`);
    }
    renderSkillControls();
    updateSlashMenu();
  }

  async function loadChatSkill(chatId) {
    const result = await jsonRequest(`/api/chats/${encodeURIComponent(chatId)}/skill`);
    state.currentSkill = result.skill;
    state.skillState = result.state;
  }

  function renderSkillControls() {
    const label = $("current-skill-label");
    if (label) {
      const name = state.currentSkill ? state.currentSkill.displayName : "Default";
      label.textContent = `Skill: ${name}`;
      label.title = "Type / to choose a skill for the next message.";
    }
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

  function preferredCommandForSkill(skill) {
    return PREFERRED_SKILL_COMMANDS[skill.slug] || skill.slug;
  }

  function resolveSkillCommand(command) {
    const normalized = command.replace(/^\//, "").trim().toLowerCase();
    if (!normalized || state.skillsLoadError) return null;
    const aliasedSlug = SKILL_ALIASES[normalized] || normalized;
    return state.skills.find((skill) => skill.slug.toLowerCase() === aliasedSlug.toLowerCase()) || null;
  }

  function parseSlashCommand(content) {
    if (!content.startsWith("/")) return null;
    if (content.trim() === "/") {
      return { command: "/", skillSlug: null, skill: null, trailing: "", isOnlyCommand: true, error: "Type / to choose a skill." };
    }
    const match = content.match(/^\/([^\s/]+)(?:\s+([\s\S]*))?$/);
    if (!match) {
      return { command: "/", skillSlug: null, skill: null, trailing: "", isOnlyCommand: true, error: "Type / to choose a skill." };
    }
    const command = `/${match[1]}`;
    const trailing = (match[2] || "").trim();
    const skill = resolveSkillCommand(command);
    if (!skill) {
      const error = state.skillsLoadError
        ? `Skills unavailable: ${state.skillsLoadError}. Normal chat still works.`
        : `Unknown skill command: ${command}. Type / to choose a skill.`;
      return { command, skillSlug: null, skill: null, trailing, isOnlyCommand: !trailing, error };
    }
    return { command, skillSlug: skill.slug, skill, trailing, isOnlyCommand: !trailing, error: null };
  }

  function slashCommandFragment(value) {
    if (!value.startsWith("/")) return null;
    const trimmed = value.trim();
    if (!trimmed.startsWith("/") || /\s/.test(trimmed)) return null;
    return trimmed.slice(1).toLowerCase();
  }

  function slashMenuMatches(fragment) {
    if (state.skillsLoadError) return [];
    const query = (fragment || "").toLowerCase();
    return state.skills
      .map((skill) => ({ skill, command: preferredCommandForSkill(skill) }))
      .filter((item) => {
        const aliasText = Object.entries(SKILL_ALIASES)
          .filter(([, slug]) => slug.toLowerCase() === item.skill.slug.toLowerCase())
          .map(([alias]) => alias)
          .join(" ");
        const haystack = `${item.command} ${item.skill.slug} ${item.skill.displayName || ""} ${item.skill.description || ""} ${aliasText}`.toLowerCase();
        return !query || haystack.includes(query);
      })
      .slice(0, 8);
  }

  function updateSlashMenu() {
    const input = $("message-input");
    const menu = $("slash-menu");
    if (!input || !menu) return;
    const fragment = slashCommandFragment(input.value);
    if (fragment == null) {
      hideSlashMenu();
      return;
    }
    if (state.skillsLoadError) {
      menu.hidden = false;
      menu.innerHTML = `<div class="slash-menu-empty">Skills unavailable: ${escapeText(state.skillsLoadError)}</div>`;
      return;
    }
    state.slashMenuItems = slashMenuMatches(fragment);
    state.slashMenuIndex = Math.min(state.slashMenuIndex, Math.max(state.slashMenuItems.length - 1, 0));
    renderSlashMenu();
  }

  function renderSlashMenu() {
    const menu = $("slash-menu");
    if (!menu) return;
    menu.hidden = false;
    if (!state.slashMenuItems.length) {
      menu.innerHTML = `<div class="slash-menu-empty">No matching skills. Type / to choose a skill.</div>`;
      return;
    }
    menu.innerHTML = "";
    state.slashMenuItems.forEach((item, index) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `slash-menu-item${index === state.slashMenuIndex ? " active" : ""}`;
      button.setAttribute("role", "option");
      button.setAttribute("aria-selected", index === state.slashMenuIndex ? "true" : "false");
      button.innerHTML = `
        <span class="slash-menu-command">/${escapeText(item.command)}</span>
        <span class="slash-menu-name">${escapeText(item.skill.displayName || item.skill.slug)}</span>
        <span class="slash-menu-description">${escapeText(item.skill.description || item.skill.slug)}</span>
      `;
      button.addEventListener("mousedown", (event) => event.preventDefault());
      button.addEventListener("click", () => selectSlashMenuItem(index));
      menu.appendChild(button);
    });
  }

  function hideSlashMenu() {
    const menu = $("slash-menu");
    if (!menu) return;
    menu.hidden = true;
    menu.innerHTML = "";
    state.slashMenuItems = [];
    state.slashMenuIndex = 0;
  }

  function selectSlashMenuItem(index = state.slashMenuIndex) {
    const item = state.slashMenuItems[index];
    if (!item) return false;
    const input = $("message-input");
    input.value = `/${item.command} `;
    input.focus();
    hideSlashMenu();
    showSkillStatus(`${item.skill.displayName || item.skill.slug} selected. Add your message after /${item.command} and press Send.`);
    return true;
  }

  function handleSlashMenuKeydown(event) {
    const menu = $("slash-menu");
    const input = $("message-input");
    if (!menu || menu.hidden || !input.value.startsWith("/")) return false;
    if (event.key === "Escape") {
      event.preventDefault();
      hideSlashMenu();
      return true;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!state.slashMenuItems.length) return true;
      const direction = event.key === "ArrowDown" ? 1 : -1;
      state.slashMenuIndex = (state.slashMenuIndex + direction + state.slashMenuItems.length) % state.slashMenuItems.length;
      renderSlashMenu();
      return true;
    }
    if (event.key === "Enter" && !event.shiftKey && slashCommandFragment(input.value) != null && state.slashMenuItems.length) {
      event.preventDefault();
      selectSlashMenuItem();
      return true;
    }
    return false;
  }

  async function sendMessage(event) {
    event.preventDefault();
    if (state.streaming) return;
    const input = $("message-input");
    let content = input.value.trim();
    if (!content) return;
    showError("chat-error", "");
    if (!state.currentChatId) {
      showError("chat-error", "No active chat. Create or select a chat before sending.");
      return;
    }
    const parsedCommand = parseSlashCommand(content);
    let skillSlugForMessage;
    if (parsedCommand) {
      if (parsedCommand.error) {
        showError("chat-error", parsedCommand.error);
        updateSlashMenu();
        return;
      }
      if (parsedCommand.isOnlyCommand) {
        const command = preferredCommandForSkill(parsedCommand.skill);
        input.value = `/${command} `;
        input.focus();
        showSkillStatus(`${parsedCommand.skill.displayName || parsedCommand.skill.slug} selected. Add your message after /${command} and press Send.`);
        hideSlashMenu();
        return;
      }
      skillSlugForMessage = parsedCommand.skillSlug;
      content = parsedCommand.trailing;
    }
    state.streaming = true;
    state.currentGenerationId = null;
    $("send-button").disabled = true;
    $("stop-button").hidden = false;
    $("retry-button").disabled = true;
    input.setAttribute("aria-busy", "true");
    input.value = "";
    appendMessage("user", content, { final: true });
    const assistantContent = appendMessage("assistant", "");
    showTypingIndicator(assistantContent);
    hideSlashMenu();
    try {
      await streamMessage(content, assistantContent, { skillSlug: skillSlugForMessage });
      await loadSkills();
      await loadChats();
    } catch (error) {
      clearTypingIndicator(assistantContent);
      const message = userFacingGenerationError(error);
      showError("chat-error", message);
      assistantContent.textContent = message;
    } finally {
      state.streaming = false;
      state.currentGenerationId = null;
      $("send-button").disabled = false;
      $("stop-button").hidden = true;
      $("retry-button").disabled = false;
      input.removeAttribute("aria-busy");
    }
  }

  async function stopGeneration() {
    if (!state.currentChatId) return;
    const path = state.currentGenerationId
      ? `/api/generations/${encodeURIComponent(state.currentGenerationId)}/cancel`
      : `/api/chats/${encodeURIComponent(state.currentChatId)}/generation/cancel`;
    try {
      await jsonRequest(path, { method: "POST", body: "{}" });
      showError("chat-error", "Generation stopped.");
      await loadChatGenerations();
    } catch (error) {
      showError("chat-error", userFacingGenerationError(error));
    }
  }

  async function retryGeneration() {
    if (!state.currentChatId || state.streaming) return;
    showError("chat-error", "");
    state.streaming = true;
    $("send-button").disabled = true;
    $("retry-button").disabled = true;
    try {
      const result = await jsonRequest(`/api/chats/${encodeURIComponent(state.currentChatId)}/generation/retry`, {
        method: "POST",
        body: "{}",
      });
      if (result.message) {
        state.messages.push(result.message);
        renderMessages();
      } else {
        await openChat(state.currentChatId);
      }
    } catch (error) {
      showError("chat-error", error.message);
    } finally {
      state.streaming = false;
      $("send-button").disabled = false;
      $("retry-button").disabled = false;
    }
  }

  async function streamMessage(content, assistantContent, options = {}) {
    const response = await fetch(`/api/chats/${encodeURIComponent(state.currentChatId)}/messages`, {
      method: "POST",
      headers: headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        content,
        stream: true,
        fileIds: state.attachedFiles.map((file) => file.id),
        skillSlug: options.skillSlug || (state.currentSkill ? state.currentSkill.slug : undefined),
        thinkingEnabled: state.skillState ? state.skillState.thinkingEnabled : undefined,
        showThinking: state.skillState ? state.skillState.showThinking : false,
      }),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      const error = new Error(body.error || `Message failed (${response.status})`);
      error.status = response.status;
      error.code = body.errorDetails ? body.errorDetails.code : body.code;
      error.details = body.errorDetails ? body.errorDetails.details : body.details;
      throw error;
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
          if (parsed.generation) state.currentGenerationId = parsed.generation.id;
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

  function userFacingGenerationError(error) {
    if (error && (error.code === "generation_active" || /Another generation is already active/i.test(error.message || ""))) {
      const source = error.details && error.details.activeGenerationSource && error.details.activeGenerationSource !== "none"
        ? ` Source: ${error.details.activeGenerationSource}.`
        : "";
      const adminHint = state.user && state.user.role === "ADMIN" ? " Open Admin -> Generations to cancel it, or restart the local server." : " Ask an admin to open Admin -> Generations to cancel it, or restart the local server.";
      return `Another generation is active, possibly in another chat or from an interrupted stream.${source}${adminHint}`;
    }
    return error ? error.message : "Request failed.";
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
    $("upload-status").textContent = "";
    const file = $("file-upload").files[0];
    if (!file) return;
    if (!file.name.toLowerCase().endsWith(".md")) {
      showError("files-error", "Only .md Markdown files are accepted.");
      $("file-upload").value = "";
      return;
    }
    try {
      if (!state.currentChatId) await createChat();
      $("upload-status").textContent = `Uploading ${file.name}...`;
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
      $("upload-status").textContent = `Attached ${file.name}.`;
    } catch (error) {
      showError("files-error", error.message);
      $("upload-status").textContent = "";
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

  async function loadModelStatus() {
    const banner = $("model-status-banner");
    try {
      const health = await jsonRequest("/health");
      banner.textContent = `${health.modelLoaded ? "Model loaded" : "Model not loaded"} - ${health.securityMode || health.serverMode || "local"}${health.storageWritable === false ? " - storage warning" : ""}`;
      banner.className = `status-banner ${health.modelLoaded ? "ok" : "warn"}`;
    } catch (_) {
      banner.textContent = "Server status unavailable.";
      banner.className = "status-banner warn";
    }
  }

  function renderAdminStatus(status) {
    const speculative = status.speculativeDecodingEnabled
      ? "enabled"
      : status.speculativeDecodingRequested
        ? "requested"
        : "disabled";
    const generation = status.generation || {};
    renderDefinitionList("system-status", [
      ["Model loaded", status.modelLoaded ? "Yes" : "No"],
      ["Backend", status.backendStatus || "Unknown"],
      ["Server mode", status.serverMode || "Unknown"],
      ["LAN IP", status.lanIp || "Unavailable"],
      ["MTP/speculative decoding", speculative],
      ["Active generation", status.activeGeneration ? `Yes (${generation.activeGenerationSource || "unknown"})` : "No"],
    ]);
  }

  async function loadAdminGenerations() {
    showError("admin-error", "");
    $("generation-admin-message").textContent = "";
    try {
      const result = await jsonRequest("/api/admin/generations");
      state.adminGenerations = result.generations || [];
      state.adminGenerationSummary = result.generation || null;
      renderAdminGenerations();
    } catch (error) {
      if (error.status === 401) $("admin-login").hidden = false;
      else showError("admin-error", error.message);
    }
  }

  function renderAdminGenerations() {
    const summary = state.adminGenerationSummary || {};
    renderDefinitionList("generation-status", [
      ["Active source", summary.activeGenerationSource || "none"],
      ["Active count", String(summary.activeCount || 0)],
      ["Job store active", summary.jobStoreActive ? `Yes (${summary.jobStoreActiveCount || 0})` : "No"],
      ["LiteRT active", summary.liteRtActive ? "Yes" : "No"],
      ["Expired stale jobs", String(summary.expiredStaleCount || 0)],
    ]);
    const body = $("admin-generations");
    body.innerHTML = "";
    if (!state.adminGenerations.length) {
      body.innerHTML = '<tr><td colspan="5" class="muted">No generation jobs recorded yet.</td></tr>';
      return;
    }
    state.adminGenerations.forEach((job) => {
      const row = document.createElement("tr");
      row.innerHTML = `
        <td>${escapeText(job.status || "unknown")}</td>
        <td>${escapeText(formatDuration(job.activeAgeMs || job.ageMs))}</td>
        <td><code>${escapeText(job.chatId || "")}</code></td>
        <td>${escapeText(job.errorMessage || job.errorCode || "-")}</td>
        <td>${job.isActive ? '<button type="button">Cancel</button>' : '<span class="muted">-</span>'}</td>
      `;
      const button = row.querySelector("button");
      if (button) button.addEventListener("click", () => cancelAdminGeneration(job.id));
      body.appendChild(row);
    });
  }

  async function cancelAllGenerations() {
    $("generation-admin-message").textContent = "";
    try {
      const result = await jsonRequest("/api/admin/generations/cancel-all-active", { method: "POST", body: "{}" });
      $("generation-admin-message").textContent = `Cancelled ${Number(result.cancelledCount || 0)} active generation job(s). LiteRT active: ${result.generation && result.generation.liteRtActive ? "yes" : "no"}.`;
      await loadAdminGenerations();
    } catch (error) {
      $("generation-admin-message").textContent = `Cancellation failed: ${error.message}`;
    }
  }

  async function cancelAdminGeneration(id) {
    $("generation-admin-message").textContent = "";
    try {
      await jsonRequest(`/api/admin/generations/${encodeURIComponent(id)}/cancel`, { method: "POST", body: "{}" });
      $("generation-admin-message").textContent = "Generation cancelled.";
      await loadAdminGenerations();
    } catch (error) {
      $("generation-admin-message").textContent = `Cancellation failed: ${error.message}`;
    }
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

  function renderAdminSkills() {
    const body = $("admin-skills");
    body.innerHTML = "";
    state.adminSkills.forEach((skill) => {
      const row = document.createElement("tr");
      row.innerHTML = `
        <td><strong>${escapeText(skill.displayName)}</strong><div class="file-meta">${escapeText(skill.slug)}</div></td>
        <td>${skill.builtIn ? "Built-in" : "Custom"}</td>
        <td>${skill.enabled ? "Yes" : "No"}</td>
        <td>${escapeText((skill.allowedTools || []).join(", ") || "-")}</td>
        <td><button type="button">Edit</button></td>
      `;
      row.querySelector("button").addEventListener("click", () => editAdminSkill(skill));
      body.appendChild(row);
    });
  }

  function editAdminSkill(skill) {
    state.selectedAdminSkill = skill;
    $("skill-slug").value = skill?.slug || "";
    $("skill-slug").disabled = Boolean(skill?.builtIn);
    $("skill-display-name").value = skill?.displayName || "";
    $("skill-display-name").disabled = Boolean(skill?.builtIn);
    $("skill-description").value = skill?.description || "";
    $("skill-description").disabled = Boolean(skill?.builtIn);
    $("skill-system-prompt").value = skill?.systemPrompt || "";
    $("skill-system-prompt").disabled = Boolean(skill?.builtIn);
    $("skill-response-mode").value = skill?.responseMode || "";
    $("skill-response-mode").disabled = Boolean(skill?.builtIn);
    $("skill-tool-use-mode").value = skill?.toolUseMode || "NONE";
    $("skill-tool-use-mode").disabled = Boolean(skill?.builtIn);
    $("skill-allowed-tools").value = (skill?.allowedTools || []).join(", ");
    $("skill-allowed-tools").disabled = Boolean(skill?.builtIn);
    $("skill-output-schema").value = skill?.outputSchema ? JSON.stringify(skill.outputSchema, null, 2) : "";
    $("skill-output-schema").disabled = Boolean(skill?.builtIn);
    $("skill-thinking-default").checked = Boolean(skill?.thinkingDefault);
    $("skill-thinking-default").disabled = Boolean(skill?.builtIn);
    $("skill-show-thinking-default").checked = Boolean(skill?.showThinkingDefault);
    $("skill-show-thinking-default").disabled = Boolean(skill?.builtIn);
    $("skill-strict-output").checked = Boolean(skill?.strictOutput);
    $("skill-strict-output").disabled = Boolean(skill?.builtIn);
    $("skill-enabled").checked = skill ? Boolean(skill.enabled) : true;
    $("skill-save-button").textContent = skill?.builtIn ? "Save enabled state" : "Save skill";
    $("skill-delete-button").textContent = skill?.builtIn ? "Disable built-in" : "Delete custom";
  }

  function skillFormPayload() {
    let outputSchema = null;
    const rawSchema = $("skill-output-schema").value.trim();
    if (rawSchema) outputSchema = JSON.parse(rawSchema);
    return {
      slug: requireInput("skill-slug"),
      displayName: requireInput("skill-display-name"),
      description: $("skill-description").value.trim(),
      systemPrompt: $("skill-system-prompt").value.trim(),
      responseMode: $("skill-response-mode").value.trim() || null,
      toolUseMode: $("skill-tool-use-mode").value,
      allowedTools: $("skill-allowed-tools").value.split(",").map((item) => item.trim()).filter(Boolean),
      outputSchema,
      thinkingDefault: $("skill-thinking-default").checked,
      showThinkingDefault: $("skill-show-thinking-default").checked,
      strictOutput: $("skill-strict-output").checked,
      enabled: $("skill-enabled").checked,
    };
  }

  async function saveAdminSkill(event) {
    event.preventDefault();
    showError("admin-error", "");
    try {
      const payload = skillFormPayload();
      const path = state.selectedAdminSkill ? `/api/admin/skills/${encodeURIComponent(state.selectedAdminSkill.slug)}` : "/api/admin/skills";
      await jsonRequest(path, { method: state.selectedAdminSkill ? "PUT" : "POST", body: JSON.stringify(payload) });
      await loadAdminDashboard();
    } catch (error) {
      showError("admin-error", error.message);
    }
  }

  async function deleteAdminSkill() {
    const skill = state.selectedAdminSkill;
    if (!skill) return;
    if (!window.confirm(skill.builtIn ? "Disable this built-in skill?" : "Delete this custom skill?")) return;
    await jsonRequest(`/api/admin/skills/${encodeURIComponent(skill.slug)}`, { method: "DELETE" });
    state.selectedAdminSkill = null;
    await loadAdminDashboard();
  }

  async function exportAdminSkills() {
    const result = await jsonRequest("/api/admin/skills/export");
    $("skill-import-export").value = JSON.stringify(result, null, 2);
  }

  async function importAdminSkills() {
    const body = $("skill-import-export").value.trim();
    if (!body) return;
    JSON.parse(body);
    await jsonRequest("/api/admin/skills/import", { method: "POST", body });
    await loadAdminDashboard();
  }

  function renderAdminTools() {
    const holder = $("admin-tools");
    holder.innerHTML = "";
    state.adminTools.forEach((tool) => {
      const item = document.createElement("details");
      item.className = "tool-card";
      item.innerHTML = `
        <summary><strong>${escapeText(tool.displayName)}</strong> <span class="file-meta">${escapeText(tool.name)} - ${escapeText(tool.dangerLevel || "SAFE")}</span></summary>
        <p>${escapeText(tool.description)}</p>
        <pre>${escapeText(JSON.stringify({ inputSchema: tool.inputSchema, outputSchema: tool.outputSchema, allowedForSkills: tool.allowedForSkills || [] }, null, 2))}</pre>
      `;
      holder.appendChild(item);
    });
  }

  function renderToolMatrix() {
    const holder = $("tool-matrix");
    const rows = state.adminSkills.map((skill) => {
      const cells = state.adminTools.map((tool) => `<td>${(skill.allowedTools || []).includes(tool.name) ? "Allowed" : "-"}</td>`).join("");
      return `<tr><th>${escapeText(skill.slug)}</th>${cells}</tr>`;
    }).join("");
    holder.innerHTML = `<table><thead><tr><th>Skill</th>${state.adminTools.map((tool) => `<th>${escapeText(tool.name)}</th>`).join("")}</tr></thead><tbody>${rows}</tbody></table>`;
  }

  function renderToolLogs() {
    const holder = $("tool-logs");
    holder.innerHTML = "";
    if (!state.adminToolLogs.length) {
      holder.innerHTML = '<p class="muted">No tool calls recorded yet.</p>';
      return;
    }
    state.adminToolLogs.forEach((log) => {
      const item = document.createElement("details");
      item.className = "recent-file";
      item.innerHTML = `
        <summary><strong>${escapeText(log.toolName)}</strong> <span class="file-meta">${escapeText(log.status)} - ${escapeText(log.skillSlug || "unknown skill")} - ${escapeText(formatDate(log.createdAtMs))}</span></summary>
        <pre>${escapeText(JSON.stringify({
          requestId: log.requestId,
          chatId: log.chatId,
          messageId: log.messageId,
          skillSlug: log.skillSlug,
          skillVersion: log.skillVersion,
          parsedToolName: log.parsedToolName,
          status: log.status,
          durationMs: log.durationMs,
          errorCode: log.errorCode,
          errorMessage: log.errorMessage,
          argumentsPreview: log.argumentsPreview,
          requestPreview: log.requestPreview,
          resultPreview: log.resultPreview,
        }, null, 2))}</pre>
      `;
      holder.appendChild(item);
    });
  }

  function renderSkillTestOptions() {
    const select = $("skill-test-select");
    select.innerHTML = "";
    state.adminSkills.filter((skill) => skill.enabled).forEach((skill) => {
      const option = document.createElement("option");
      option.value = skill.slug;
      option.textContent = `${skill.displayName} (${skill.slug})`;
      select.appendChild(option);
    });
  }

  async function runSkillTest(event) {
    event.preventDefault();
    $("skill-test-output").textContent = "";
    try {
      const result = await jsonRequest("/api/admin/skills/test", {
        method: "POST",
        body: JSON.stringify({ skillSlug: $("skill-test-select").value, prompt: $("skill-test-prompt").value }),
      });
      $("skill-test-output").textContent = result.response || JSON.stringify(result, null, 2);
    } catch (error) {
      $("skill-test-output").textContent = error.message;
    }
  }

  async function downloadBackup() {
    const bundle = await jsonRequest("/api/admin/ops/export");
    downloadJson(`android-host-llm-backup-${Date.now()}.json`, bundle);
    showOpsOutput({ status: "downloaded", schemaVersion: bundle.schemaVersion, exportedAtMs: bundle.exportedAtMs });
  }

  async function downloadDiagnostics() {
    const diagnostics = await jsonRequest("/api/admin/ops/diagnostics");
    downloadJson(`android-host-llm-diagnostics-${Date.now()}.json`, diagnostics);
    showOpsOutput({ status: "downloaded", schemaVersion: diagnostics.schemaVersion, exportedAtMs: diagnostics.exportedAtMs, counts: diagnostics.counts });
  }

  async function scanStorage() {
    const scan = await jsonRequest("/api/admin/ops/storage/scan");
    showOpsOutput(scan);
  }

  async function cleanupStorage() {
    const confirmText = window.prompt('Type cleanup-orphans to remove only orphaned maintenance rows/files.');
    if (confirmText == null) return;
    const result = await jsonRequest("/api/admin/ops/storage/cleanup", {
      method: "POST",
      body: JSON.stringify({ confirm: confirmText }),
    });
    showOpsOutput(result);
    await loadAdminDashboard();
  }

  function showOpsOutput(value) {
    const output = $("ops-output");
    if (!output) return;
    output.textContent = JSON.stringify(value, null, 2);
  }

  function downloadJson(filename, value) {
    const blob = new Blob([JSON.stringify(value, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
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

  function formatDuration(ms) {
    const value = Number(ms || 0);
    if (!value) return "0s";
    const seconds = Math.floor(value / 1000);
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${minutes % 60}m`;
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
