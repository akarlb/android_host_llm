# Phase 4 Frontend Manual Test Checklist

Run against an installed APK with the phone server started.

- Register/login and open `/chat`.
- Confirm admin link visibility matches user role.
- Confirm model status banner shows model loaded/unloaded and security mode.
- Create multiple chats, search/filter the chat list, rename a chat, and archive a chat.
- Confirm active chat highlighting and updated timestamps are visible.
- Send a message; confirm Send disables, Stop appears while active, and Retry remains available afterward.
- Copy a user message and assistant message.
- Upload a valid `.md` file and confirm upload/attached status appears.
- Try an invalid non-`.md` file and confirm the rejection is clear.
- Confirm attached file chips can be removed.
- Send Markdown containing code blocks, malformed lists, and links.
- Confirm `javascript:`, `data:`, and raw HTML are not executable/rendered as HTML.
- Resize to a mobile viewport and confirm sidebar, composer, message toolbar, and buttons remain usable without overlap.
- Confirm skills and thinking controls still work.
- Confirm model-unloaded generation errors do not leave the fake typing indicator active.
