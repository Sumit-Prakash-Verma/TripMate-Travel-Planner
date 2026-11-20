const tripForm = document.getElementById("trip-form");
const tripList = document.getElementById("trip-list");
const tripCount = document.getElementById("trip-count");
const totalBudget = document.getElementById("total-budget");
const statusMessage = document.getElementById("status-message");

const tripIdInput = document.getElementById("trip-id");
const formHeading = document.getElementById("form-heading");
const saveButton = document.getElementById("save-button");
const cancelEditButton = document.getElementById("cancel-edit");
const refreshButton = document.getElementById("refresh-button");


// Authentication elements
const authForms = document.getElementById("auth-forms");
const authStatus = document.getElementById("auth-status");
const loginForm = document.getElementById("login-form");
const registerForm = document.getElementById("register-form");
const userPanel = document.getElementById("user-panel");
const currentUsername = document.getElementById("current-username");
const loginButton = document.getElementById("login-button");
const registerButton = document.getElementById("register-button");
const logoutButton = document.getElementById("logout-button");

const API_URL = "/api/trips";
const AUTH_URL = "/api/auth";

// Edit Itinerary Modal Elements
const editModal = document.getElementById("edit-itinerary-modal");
const editForm = document.getElementById("edit-itinerary-form");

const editId = document.getElementById("edit-itinerary-id");
const editDestination = document.getElementById("edit-destination");
const editStartDate = document.getElementById("edit-start-date");
const editEndDate = document.getElementById("edit-end-date");
const editBudget = document.getElementById("edit-budget");

const cancelItineraryModalButton = document.getElementById("cancel-edit-button");
const updateItineraryButton = document.getElementById("update-itinerary-button");

let currentUser = null;
let ownedTripIds = new Set();
let loadingTrips = false;

const currencyFormatter = new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 2
});

// ---------------- STATUS MESSAGES ----------------

function showStatus(message, isError = false) {
    statusMessage.textContent = message;
    statusMessage.classList.toggle("error", isError);
    statusMessage.hidden = false;
}

function clearStatus() {
    statusMessage.textContent = "";
    statusMessage.hidden = true;
    statusMessage.classList.remove("error");
}

function showAuthStatus(message, isError = false) {
    authStatus.textContent = message;
    authStatus.classList.toggle("error", isError);
    authStatus.hidden = false;
}

function clearAuthStatus() {
    authStatus.textContent = "";
    authStatus.hidden = true;
    authStatus.classList.remove("error");
}

// ---------------- API REQUEST ----------------

async function apiRequest(url, options = {}) {
    const response = await fetch(url, {
        credentials: "same-origin",
        ...options
    });

    const responseText = await response.text();
    let data = null;

    if (responseText) {
        try {
            data = JSON.parse(responseText);
        } catch {
            data = responseText;
        }
    }

    if (!response.ok) {
       const message =
    data && typeof data === "object"
        ? (data.error || data.message || `Request failed: HTTP ${response.status}`)
        : `Request failed: HTTP ${response.status}`;

        const error = new Error(message);
        error.status = response.status;
        throw error;
    }

    return data;
}

// ---------------- AUTHENTICATION ----------------

function updateAuthUI(user) {
    currentUser = user;

    const loggedIn = Boolean(user);

    authForms.hidden = loggedIn;
    userPanel.hidden = !loggedIn;

    currentUsername.textContent = loggedIn
        ? `${user.fullName} (${user.email})`
        : "";

    // Trip operations require login.
    tripForm.querySelectorAll("input, textarea, button").forEach((element) => {
        element.disabled = !loggedIn;
    });

    refreshButton.disabled = !loggedIn;

    if (!loggedIn) {
        ownedTripIds.clear();
        resetForm();
        tripCount.textContent = "0";
        totalBudget.textContent = currencyFormatter.format(0);

        tripList.replaceChildren(
            createElement(
                "p",
                "empty-state",
                "Please log in to view and manage your trips."
            )
        );
    }
}

async function checkCurrentUser() {
    try {
        const user = await apiRequest(`${AUTH_URL}/me`);

        updateAuthUI(user);
clearAuthStatus();

await loadTrips();
await loadSavedItineraries();
    } catch (error) {
        updateAuthUI(null);

        if (error.status !== 401 && error.status !== 403) {
            showAuthStatus(
                `Could not check login status: ${error.message}`,
                true
            );
        }
    }
}

// LOGIN
loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearAuthStatus();

    const email = document.getElementById("login-email").value.trim();
    const password = document.getElementById("login-password").value;

    loginButton.disabled = true;
    loginButton.textContent = "Logging in...";

    try {
        const user = await apiRequest(`${AUTH_URL}/login`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ email, password })
        });

       updateAuthUI(user);
loginForm.reset();

showAuthStatus(`Welcome, ${user.fullName}! Login successful.`);

await loadTrips();
await loadSavedItineraries();
    } catch (error) {
        showAuthStatus(`Login failed: ${error.message}`, true);
    } finally {
        loginButton.disabled = false;
        loginButton.textContent = "Login";
    }
});

// REGISTER
registerForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearAuthStatus();

    const fullName = document
        .getElementById("register-full-name").value.trim();

    const email = document
        .getElementById("register-email").value.trim();

    const password = document
        .getElementById("register-password").value;

    if (password.length < 8) {
        showAuthStatus(
            "Password must contain at least 8 characters.",
            true
        );
        return;
    }

    registerButton.disabled = true;
    registerButton.textContent = "Registering...";

    try {
        await apiRequest(`${AUTH_URL}/register`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ fullName, email, password })
        });

        registerForm.reset();

        document.getElementById("login-email").value = email;
        document.getElementById("login-password").focus();

        showAuthStatus(
            "Account created successfully! Please log in."
        );
    } catch (error) {
        showAuthStatus(`Registration failed: ${error.message}`, true);
    } finally {
        registerButton.disabled = false;
        registerButton.textContent = "Register";
    }
});

// LOGOUT
logoutButton.addEventListener("click", async () => {
    logoutButton.disabled = true;
    clearAuthStatus();

    try {
        await apiRequest(`${AUTH_URL}/logout`, {
            method: "POST"
        });

        updateAuthUI(null);
        showAuthStatus("You have logged out successfully.");
    } catch (error) {
        showAuthStatus(`Logout failed: ${error.message}`, true);
    } finally {
        logoutButton.disabled = false;
    }
});

// ---------------- TRIP HELPERS ----------------

function formatDate(dateString) {
    if (!dateString) {
        return "Not specified";
    }

    const parts = dateString.split("-");

    if (parts.length !== 3) {
        return dateString;
    }

    return `${parts[2]}-${parts[1]}-${parts[0]}`;
}

function createElement(tag, className, text) {
    const element = document.createElement(tag);

    if (className) {
        element.className = className;
    }

    if (text !== undefined) {
        element.textContent = text;
    }

    return element;
}

// ---------------- TRIP MEMBERS ----------------

async function loadTripMembers(tripId, container, button) {
    button.disabled = true;
    button.textContent = "Loading members...";

    const oldList = container.querySelector(".members-list");
    if (oldList) {
        oldList.remove();
    }

    const message = container.querySelector(".members-message");
    message.hidden = true;
    message.textContent = "";
    message.classList.remove("error");

    try {
        const members = await apiRequest(
            `${API_URL}/${encodeURIComponent(tripId)}/members`
        );

        const list = createElement("div", "members-list");

        if (!Array.isArray(members) || members.length === 0) {
            message.textContent = "No members found.";
            message.hidden = false;
            button.textContent = "View Members";
            button.dataset.loaded = "false";
            return;
        }

        members.forEach((member) => {
            const memberCard = createElement("div", "member-item");

            const name = createElement(
                "p",
                "member-name",
                member.fullName || "Unnamed user"
            );

            const email = createElement(
                "p",
                "member-email",
                member.email || ""
            );

            const role = createElement(
                "span",
                `member-role ${
                    member.role === "OWNER" ? "owner-role" : "member-role-badge"
                }`,
                member.role || "MEMBER"
            );

            memberCard.append(name, email, role);

            // Only the owner sees a Remove button for non-owner members.
            if (ownedTripIds.has(String(tripId)) && member.role !== "OWNER") {
                const removeButton = createElement(
                    "button",
                    "remove-member-btn",
                    "Remove"
                );
                removeButton.type = "button";
                removeButton.setAttribute(
                    "aria-label",
                    `Remove ${member.email || member.fullName} from trip`
                );

                removeButton.addEventListener("click", async () => {
                    const memberEmail = (member.email || "").trim();

                    if (!memberEmail) {
                        message.textContent =
                            "Cannot remove this member because their email is missing.";
                        message.classList.add("error");
                        message.hidden = false;
                        return;
                    }

                    const confirmed = window.confirm(
                        `Remove ${member.fullName || memberEmail} from this trip?`
                    );

                    if (!confirmed) {
                        return;
                    }

                    removeButton.disabled = true;
                    removeButton.textContent = "Removing...";
                    message.hidden = true;
                    message.textContent = "";
                    message.classList.remove("error");

                    try {
                        const result = await apiRequest(
                            `${API_URL}/${encodeURIComponent(tripId)}/members?email=${encodeURIComponent(memberEmail)}`,
                            { method: "DELETE" }
                        );

                        message.textContent =
                            result?.message || "Member removed successfully.";
                        message.hidden = false;

                        // Refresh the visible members list after removal.
                        await loadTripMembers(tripId, container, button);
                    } catch (error) {
                        message.textContent =
                            `Could not remove member: ${error.message}`;
                        message.classList.add("error");
                        message.hidden = false;
                        removeButton.disabled = false;
                        removeButton.textContent = "Remove";
                    }
                });

                memberCard.append(removeButton);
            }

            list.append(memberCard);
        });

        container.append(list);
        button.textContent = "Hide Members";
        button.dataset.loaded = "true";
    } catch (error) {
        message.textContent = `Could not load members: ${error.message}`;
        message.classList.add("error");
        message.hidden = false;
        button.textContent = "Retry Members";
        button.dataset.loaded = "false";
    } finally {
        button.disabled = false;
    }
}

// ---------------- TRIP CARD ----------------

function createTripCard(trip) {
    const isOwner = ownedTripIds.has(String(trip.id));
    const card = createElement("article", "trip-card");

    const title = createElement("h3", "", trip.title);

    const destination = createElement(
        "p",
        "trip-destination",
        `📍 ${trip.destination}`
    );

    const details = createElement("div", "trip-details");

    details.append(
        createElement("p", "", `📅 Start: ${formatDate(trip.startDate)}`),
        createElement("p", "", `📅 End: ${formatDate(trip.endDate)}`),
        createElement(
            "p",
            "",
            `💰 Budget: ${
                trip.budget != null
                    ? currencyFormatter.format(Number(trip.budget))
                    : "Not specified"
            }`
        )
    );

    if (trip.description) {
        details.append(
            createElement("p", "trip-description", trip.description)
        );
    }

    card.append(title, destination, details);

    // Members section: owner and shared-trip members can view the list.
    const membersSection = createElement("section", "trip-members-section");
    const membersHeading = createElement("h4", "", "👥 Trip Members");

    const membersButton = createElement(
        "button",
        "members-btn",
        "View Members"
    );
    membersButton.type = "button";

    const membersMessage = createElement("p", "members-message");
    membersMessage.hidden = true;

    membersSection.append(membersHeading, membersButton, membersMessage);
    card.append(membersSection);

    membersButton.addEventListener("click", async () => {
        const list = membersSection.querySelector(".members-list");

        if (list) {
            list.remove();
            membersButton.textContent = "View Members";
            membersButton.dataset.loaded = "false";
            membersMessage.hidden = true;
            return;
        }

        await loadTripMembers(trip.id, membersSection, membersButton);
    });

    // Edit and Delete are owner-only.
    if (isOwner) {
        const actions = createElement("div", "trip-actions");

        const editButton = createElement("button", "edit-btn", "✏ Edit");
        editButton.type = "button";
        editButton.addEventListener("click", () => startEditing(trip));

        const deleteButton = createElement("button", "delete-btn", "🗑 Delete");
        deleteButton.type = "button";
        deleteButton.addEventListener("click", () => deleteTrip(trip.id));

        actions.append(editButton, deleteButton);
        card.append(actions);

        // Invite Member form — owner-only.
        const inviteForm = createElement("form", "invite-form");
        const emailInput = createElement("input", "invite-email");

        emailInput.type = "email";
        emailInput.placeholder = "Member's email address";
        emailInput.required = true;
        emailInput.setAttribute("aria-label", "Member email");

        const inviteButton = createElement("button", "invite-btn", "Invite Member");
        inviteButton.type = "submit";

        const inviteMessage = createElement("p", "invite-message");
        inviteMessage.hidden = true;

        inviteForm.append(emailInput, inviteButton, inviteMessage);

        inviteForm.addEventListener("submit", async (event) => {
            event.preventDefault();

            const email = emailInput.value.trim();

            inviteButton.disabled = true;
            inviteButton.textContent = "Inviting...";
            inviteMessage.hidden = true;

            try {
                const result = await apiRequest(
                    `${API_URL}/${encodeURIComponent(trip.id)}/invite`,
                    {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ email })
                    }
                );

                inviteMessage.textContent =
                    result.message || "Member added successfully!";
                inviteMessage.classList.remove("error");
                inviteMessage.hidden = false;
                emailInput.value = "";

                const visibleList =
                    membersSection.querySelector(".members-list");

                if (visibleList) {
                    await loadTripMembers(
                        trip.id,
                        membersSection,
                        membersButton
                    );
                }
            } catch (error) {
                inviteMessage.textContent = error.message;
                inviteMessage.classList.add("error");
                inviteMessage.hidden = false;
            } finally {
                inviteButton.disabled = false;
                inviteButton.textContent = "Invite Member";
            }
        });

        card.append(inviteForm);
    }

    return card;
}

// ---------------- LOAD TRIPS ----------------

async function loadTrips() {
    if (!currentUser || loadingTrips) {
        return;
    }

    loadingTrips = true;
    refreshButton.disabled = true;

    tripList.replaceChildren(
        createElement("p", "empty-state", "Loading trips...")
    );

    try {
        const trips = await apiRequest(API_URL);
        const ownedIds = await apiRequest(`${API_URL}/owned-ids`);
        ownedTripIds = new Set(ownedIds.map(id => String(id)));

        if (!Array.isArray(trips)) {
            throw new Error("Unexpected response from the server.");
        }

        if (!currentUser) {
            return;
        }

        tripCount.textContent = trips.length;

        const budgetSum = trips.reduce((total, trip) => {
            const amount = Number(trip.budget);
            return total + (
                Number.isFinite(amount) && amount > 0 ? amount : 0
            );
        }, 0);

        totalBudget.textContent = currencyFormatter.format(budgetSum);
        tripList.replaceChildren();

        if (trips.length === 0) {
            tripList.append(
                createElement(
                    "p",
                    "empty-state",
                    "No trips yet. Create your first adventure!"
                )
            );
            return;
        }

        trips.forEach((trip) => {
            tripList.append(createTripCard(trip));
        });
    } catch (error) {
        if (error.status === 401 || error.status === 403) {
            updateAuthUI(null);
            showAuthStatus("Your session has expired. Please log in again.");
            return;
        }

        tripList.replaceChildren(
            createElement(
                "p",
                "empty-state",
                `Could not load trips: ${error.message}`
            )
        );
    } finally {
        loadingTrips = false;
        refreshButton.disabled = !currentUser;
    }
}

// ---------------- CREATE / EDIT TRIP ----------------

function resetForm() {
    tripForm.reset();
    tripIdInput.value = "";
    formHeading.textContent = "Create a Trip";
    saveButton.textContent = "Create Trip";
    cancelEditButton.hidden = true;
    clearStatus();
}

function startEditing(trip) {
    if (!currentUser) {
        showAuthStatus("Please log in to edit trips.", true);
        return;
    }

    clearStatus();

    tripIdInput.value = trip.id;
    document.getElementById("title").value = trip.title ?? "";
    document.getElementById("destination").value = trip.destination ?? "";
    document.getElementById("startDate").value = trip.startDate ?? "";
    document.getElementById("endDate").value = trip.endDate ?? "";
    document.getElementById("budget").value = trip.budget ?? "";
    document.getElementById("description").value = trip.description ?? "";

    formHeading.textContent = `Edit Trip #${trip.id}`;
    saveButton.textContent = "Update Trip";
    cancelEditButton.hidden = false;

    tripForm.scrollIntoView({
        behavior: "smooth",
        block: "start"
    });
}

tripForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearStatus();

    if (!currentUser) {
        showStatus("Please log in before creating or updating a trip.", true);
        return;
    }

    const id = tripIdInput.value;
    const startDate = document.getElementById("startDate").value;
    const endDate = document.getElementById("endDate").value;
    const budgetValue = document.getElementById("budget").value;

    if (startDate && endDate && endDate < startDate) {
        showStatus("End date cannot be before the start date.", true);
        return;
    }

    if (budgetValue !== "" && Number(budgetValue) < 0) {
        showStatus("Budget cannot be negative.", true);
        return;
    }

    const trip = {
        title: document.getElementById("title").value.trim(),
        destination: document.getElementById("destination").value.trim(),
        startDate: startDate || null,
        endDate: endDate || null,
        budget: budgetValue === "" ? null : Number(budgetValue),
        description: document.getElementById("description").value.trim() || null
    };

    if (!trip.title || !trip.destination) {
        showStatus("Please enter a trip title and destination.", true);
        return;
    }

    saveButton.disabled = true;

    try {
        const url = id
            ? `${API_URL}/${encodeURIComponent(id)}`
            : API_URL;

        const result = await apiRequest(url, {
            method: id ? "PUT" : "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(trip)
        });

        resetForm();

        showStatus(
            id
                ? "Trip updated successfully!"
                : `Trip created successfully! ID: ${result.id}`
        );

        await loadTrips();
    } catch (error) {
        showStatus(`Unable to save trip: ${error.message}`, true);
    } finally {
        saveButton.disabled = !currentUser;
    }
});

// ---------------- DELETE TRIP ----------------

async function deleteTrip(id) {
    if (!currentUser) {
        showAuthStatus("Please log in to delete trips.", true);
        return;
    }

    const confirmed = window.confirm(
        `Are you sure you want to delete trip #${id}?`
    );

    if (!confirmed) {
        return;
    }

    clearStatus();

    try {
        await apiRequest(`${API_URL}/${encodeURIComponent(id)}`, {
            method: "DELETE"
        });

        if (tripIdInput.value === String(id)) {
            resetForm();
        }

        showStatus("Trip deleted successfully!");
        await loadTrips();
    } catch (error) {
        showStatus(`Unable to delete trip: ${error.message}`, true);
    }
}

// ---------------- BUTTONS & INITIALIZATION ----------------

cancelEditButton.addEventListener("click", resetForm);
refreshButton.addEventListener("click", loadTrips);

// Check whether a session already exists when the page opens.
updateAuthUI(null);
checkCurrentUser();


// ---------------- AI TRIP PLANNER ----------------

const AI_API_URL = "/api/ai/plan";

const aiPlanForm = document.getElementById("ai-plan-form");
const aiDestination = document.getElementById("ai-destination");
const aiStartDate = document.getElementById("ai-start-date");
const aiEndDate = document.getElementById("ai-end-date");
const aiBudget = document.getElementById("ai-budget");
const aiGenerateButton = document.getElementById("ai-generate-button");
const aiStatus = document.getElementById("ai-status");
const aiItinerary = document.getElementById("ai-itinerary");
const saveItineraryButton = document.getElementById("save-itinerary-button");

let lastGeneratedItinerary = null;

function showAIStatus(message, isError = false) {
    if (!aiStatus) return;

    aiStatus.textContent = message;
    aiStatus.classList.toggle("error", isError);
    aiStatus.hidden = false;
}

function clearAIStatus() {
    if (!aiStatus) return;

    aiStatus.textContent = "";
    aiStatus.hidden = true;
    aiStatus.classList.remove("error");
}

// Format date safely.
function formatAIPlanDate(date) {
    if (!date) return "Not specified";

    const parts = date.split("-");
    if (parts.length !== 3) return date;

    return `${parts[2]}-${parts[1]}-${parts[0]}`;
}

// Extract a value from itinerary text.

function getAIPlanValue(text, label) {
    const cleanText = String(text || "")
        .replace(/\r/g, "")
        .replace(/[*`_]/g, "");

    const regex = new RegExp(
        "^\\s*" + label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") +
        "\\s*:\\s*(.*?)\\s*$",
        "im"
    );

    const match = cleanText.match(regex);

    return match ? match[1].trim() : "";
}
// Calculate actual total from Budget Breakdown.
function getAIBudgetBreakdownTotal(text) {
    const lines = String(text || "").split(/\r?\n/);

    let insideBudgetSection = false;
    let total = 0;
    let foundAmount = false;

    for (const originalLine of lines) {
        const line = originalLine
            .trim()
            .replace(/^[-*•]\s*/, "")
            .replace(/\*+/g, "");

        if (/^Budget Breakdown\b/i.test(line)) {
            insideBudgetSection = true;
            continue;
        }

        if (/^Travel Tips\b/i.test(line)) {
            break;
        }

        if (!insideBudgetSection || !line) {
            continue;
        }

        const match = line.match(
            /^(.+?):\s*(?:INR|₹|Rs\.?)?\s*([\d,]+(?:\.\d{1,2})?)\s*$/i
        );

        if (!match) {
            continue;
        }

        if (/^Total Estimated Cost$/i.test(match[1].trim())) {
            continue;
        }

        const amount = Number(match[2].replace(/,/g, ""));

        if (Number.isFinite(amount)) {
            total += amount;
            foundAmount = true;
        }
    }

    return foundAmount ? total : null;
}

// Create safe HTML elements using textContent.
function createAIElement(tag, className, text) {
    const element = document.createElement(tag);

    if (className) {
        element.className = className;
    }

    if (text !== undefined) {
        element.textContent = text;
    }

    return element;
}


function renderAIItinerary(itineraryText) {

    if (!aiItinerary) return;

    aiItinerary.replaceChildren();

    const text = String(itineraryText || "");

      
    // ---------------- TRIP SUMMARY ----------------

    const destination =
        getAIPlanValue(text, "Destination") ||
        aiDestination?.value.trim() ||
        "";

    const startDate =
        getAIPlanValue(text, "Start Date") ||
        getAIPlanValue(text, "Travel Start Date") ||
        aiStartDate?.value ||
        "";

    const endDate =
        getAIPlanValue(text, "End Date") ||
        getAIPlanValue(text, "Travel End Date") ||
        aiEndDate?.value ||
        "";

    const maximumBudget =
        getAIPlanValue(text, "Maximum Budget") ||
        getAIPlanValue(text, "Total Budget") ||
        (aiBudget?.value
            ? `INR ${Number(aiBudget.value).toLocaleString("en-IN")}`
            : "");

  const breakdownTotal = getAIBudgetBreakdownTotal(text);

const estimatedCost = breakdownTotal !== null
    ? `INR ${breakdownTotal.toLocaleString("en-IN")}`
    : getAIPlanValue(text, "Total Estimated Cost");

    let totalDays = getAIPlanValue(text, "Total Days");

    // Normalize Total Days
    if (totalDays) {
        const match = totalDays.match(/\d+/);
        totalDays = match ? match[0] : "";
    }

    // Calculate total days if AI does not provide it
    if (!totalDays && startDate && endDate) {
        const startParts = startDate.split("-").map(Number);
        const endParts = endDate.split("-").map(Number);

        if (startParts.length === 3 && endParts.length === 3) {
            const start = Date.UTC(
                startParts[0],
                startParts[1] - 1,
                startParts[2]
            );

            const end = Date.UTC(
                endParts[0],
                endParts[1] - 1,
                endParts[2]
            );

            if (end >= start) {
                totalDays = String(
                    Math.round((end - start) / 86400000) + 1
                );
            }
        }
    }

    const summaryCard = createAIElement(
        "section",
        "ai-summary-card"
    );

    summaryCard.append(
        createAIElement(
            "h3",
            "",
            destination || "Your Destination"
        ),

        createAIElement(
            "p",
            "",
            `📅 ${formatAIPlanDate(startDate)} to ${formatAIPlanDate(endDate)}`
        ),

        createAIElement(
            "p",
            "",
            `🗓️ ${totalDays || "N/A"} Days`
        ),

        createAIElement(
            "h3",
            "ai-total-budget",
            `💰 Maximum Budget: ${maximumBudget || "Not available"}`
        )
    );

    // Estimated expenses
    if (estimatedCost) {
        summaryCard.append(
            createAIElement(
                "p",
                "ai-estimated-cost",
                `Estimated Expenses: ${estimatedCost}`
            )
        );
    }

    // Remaining budget calculation
    const maxAmount = Number(
        String(maximumBudget).replace(/[^\d.]/g, "")
    );

    const estimatedAmount = Number(
        String(estimatedCost).replace(/[^\d.]/g, "")
    );

    if (
        maximumBudget &&
        estimatedCost &&
        Number.isFinite(maxAmount) &&
        Number.isFinite(estimatedAmount)
    ) {
        const remainingBudget = maxAmount - estimatedAmount;

        if (remainingBudget >= 0) {
            summaryCard.append(
                createAIElement(
                    "p",
                    "",
                    `Remaining Budget: INR ${remainingBudget.toLocaleString("en-IN")}`
                )
            );
        }
    }



    aiItinerary.append(summaryCard);
    // Parse response line by line
    const lines = text.split(/\r?\n/);

    const days = [];
    const budgetItems = [];
    const travelTips = [];

    let currentDay = null;
    let currentSection = "days";

    lines.forEach((originalLine) => {

       const line = originalLine
    .trim()
    .replace(/^#+\s*/, "")
    .replace(/^\*{1,2}\s*/, "")
    .replace(/\*{1,2}\s*$/, "")
    .replace(/^[-•]\s*/, "")
    .trim();

        if (!line) return;

        // Budget section
        if (/^Budget Breakdown\b/i.test(line)) {
            currentSection = "budget";
            currentDay = null;
            return;
        }

        // Travel tips section
        if (/^Travel Tips\b/i.test(line)) {
            currentSection = "tips";
            currentDay = null;
            return;
        }

        if (currentSection === "days") {

            const dayMatch = line.match(
                /^Day\s+(\d+)\s*[:–—-]?\s*(.*)$/i
            );

            if (dayMatch) {

                currentDay = {
                    day: dayMatch[1],
                    date: "",
                    morning: "",
                    afternoon: "",
                    evening: ""
                };

                const dateMatch = dayMatch[2].match(
                    /\b\d{4}-\d{2}-\d{2}\b/
                );

                if (dateMatch) {
                    currentDay.date = dateMatch[0];
                }

                days.push(currentDay);
                return;
            }

            if (!currentDay) return;

            const activityMatch = line.match(
                /^[-*•]?\s*(Morning|Afternoon|Evening)(?:\s+Activity)?\s*:\s*(.+)$/i
            );

            if (activityMatch) {

                const period = activityMatch[1].toLowerCase();

                currentDay[period] = activityMatch[2].trim();
            }
        }

        else if (currentSection === "budget") {

            const budgetMatch = line.match(
                /^[-*•]?\s*(.+?):\s*(.+)$/
            );

            if (
                budgetMatch &&
                !/^Total Estimated Cost$/i.test(budgetMatch[1].trim())
            ) {
                budgetItems.push({
                    label: budgetMatch[1].trim(),
                    amount: budgetMatch[2].trim()
                });
            }
        }

        else if (currentSection === "tips") {

            const tipMatch = line.match(
                /^(?:[-*•]\s*)?\d+[.)]\s*(.+)$/
            );

            if (tipMatch) {
                travelTips.push(tipMatch[1].trim());
            } else {
                const bulletMatch = line.match(/^[-*•]\s*(.+)$/);

                if (bulletMatch) {
                    travelTips.push(bulletMatch[1].trim());
                }
            }
        }
    });

    // Day-wise cards
    if (days.length > 0) {

        const daySection = createAIElement(
            "section",
            "ai-days-section"
        );

        daySection.append(
            createAIElement(
                "h3",
                "ai-section-title",
                "🗺️ Day-wise Travel Plan"
            )
        );

        days.forEach((day) => {

            const dayCard = createAIElement(
                "article",
                "ai-day-card"
            );

            dayCard.append(
                createAIElement("h4", "", `Day ${day.day}`)
            );

            if (day.date) {
                dayCard.append(
                    createAIElement("p", "", `📅 ${day.date}`)
                );
            }

            const activities = [
                { key: "morning", label: "Morning", icon: "🌅" },
                { key: "afternoon", label: "Afternoon", icon: "☀️" },
                { key: "evening", label: "Evening", icon: "🌙" }
            ];

            activities.forEach((activity) => {

                if (day[activity.key]) {

                    dayCard.append(
                        createAIElement(
                            "p",
                            "ai-activity",
                            `${activity.icon} ${activity.label}: ${day[activity.key]}`
                        )
                    );
                }
            });

            daySection.append(dayCard);
        });

        aiItinerary.append(daySection);
    }

    // Budget cards
    if (budgetItems.length > 0) {

        const budgetSection = createAIElement(
            "section",
            "ai-budget-section"
        );

        budgetSection.append(
            createAIElement(
                "h3",
                "ai-section-title",
                "💰 Budget Breakdown"
            )
        );

        const budgetGrid = createAIElement(
            "div",
            "ai-budget-grid"
        );

        const budgetIcons = {
            "accommodation": "🏨",
            "hotel": "🏨",
            "food": "🍽️",
            "local transport": "🚕",
            "activities": "🎯",
            "emergency / extra": "🛟"
        };

        budgetItems.forEach((item) => {

            const card = createAIElement(
                "div",
                "ai-budget-card"
            );

            const icon =
                budgetIcons[item.label.toLowerCase()] || "💰";

            card.append(
                createAIElement("span", "", icon),
                createAIElement("p", "", item.label),
                createAIElement("strong", "", item.amount)
            );

            budgetGrid.append(card);
        });

        budgetSection.append(budgetGrid);
        aiItinerary.append(budgetSection);
    }

    // Travel tips
    if (travelTips.length > 0) {

        const tipsSection = createAIElement(
            "section",
            "ai-tips-section"
        );

        tipsSection.append(
            createAIElement(
                "h3",
                "ai-section-title",
                "💡 Travel Tips"
            )
        );

        const tipsList = createAIElement(
            "ul",
            "ai-tips-list"
        );

        travelTips.forEach((tip) => {

            tipsList.append(
                createAIElement("li", "", tip)
            );
        });

        tipsSection.append(tipsList);
        aiItinerary.append(tipsSection);
    }

    // Fallback
    if (days.length === 0) {

        aiItinerary.append(
            createAIElement(
                "pre",
                "ai-itinerary-output",
                text
            )
        );
    }
}

// Generate itinerary.
if (aiPlanForm) {

    aiPlanForm.addEventListener("submit", async (event) => {
    event.preventDefault();

    // Purani generated itinerary clear karein
    lastGeneratedItinerary = null;

    if (saveItineraryButton) {
        saveItineraryButton.hidden = true;
    }


        clearAIStatus();

        if (aiItinerary) {
            aiItinerary.replaceChildren();
        }

        if (!currentUser) {
            showAIStatus(
                "Please log in before generating a trip itinerary.",
                true
            );
            return;
        }

        const destination = aiDestination?.value.trim();
        const startDate = aiStartDate?.value;
        const endDate = aiEndDate?.value;
        const budgetValue = aiBudget?.value;

        if (!destination || !startDate || !endDate || !budgetValue) {
            showAIStatus("Please fill in all planner fields.", true);
            return;
        }

        if (endDate < startDate) {
            showAIStatus("End date cannot be before start date.", true);
            return;
        }

        const today = new Date();
        const localToday =
            `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;

        if (startDate < localToday) {
            showAIStatus("Please select a future travel date.", true);
            return;
        }

        const budget = Number(budgetValue);

        if (!Number.isFinite(budget) || budget <= 0) {
            showAIStatus("Please enter a valid positive budget.", true);
            return;
        }

        const requestData = {
            destination,
            startDate,
            endDate,
            budget
        };

        aiGenerateButton.disabled = true;
        aiGenerateButton.textContent = "Generating Plan...";

        showAIStatus("Ollama AI is generating your itinerary...");

        try {

            const result = await apiRequest(AI_API_URL, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(requestData)
            });

            if (!result || !result.itinerary) {
                throw new Error("No itinerary was returned by the server.");
            }

           renderAIItinerary(result.itinerary);

lastGeneratedItinerary = result.itinerary;

if (saveItineraryButton) {
    saveItineraryButton.hidden = false;
    saveItineraryButton.disabled = false;
    saveItineraryButton.textContent = "💾 Save Itinerary to My Trips";
}

showAIStatus("Your AI itinerary was generated successfully using Ollama + Qwen 2.5:7b!");

        } catch (error) {

            console.error("Trip planner error:", error);

            showAIStatus(
                `Unable to generate itinerary: ${error.message}`,
                true
            );

        } finally {

            aiGenerateButton.disabled = false;
            aiGenerateButton.textContent = "Generate AI Plan";
        }
    });

} else {
    console.warn("AI Planner form not found.");
}

// ---------------- SAVE GENERATED ITINERARY ----------------

if (saveItineraryButton) {

    saveItineraryButton.addEventListener("click", async () => {

        if (!currentUser) {
            showAIStatus("Please log in to save your itinerary.", true);
            return;
        }

        if (!lastGeneratedItinerary) {
            showAIStatus("Please generate an itinerary first.", true);
            return;
        }

        const requestData = {
            destination: aiDestination.value.trim(),
            startDate: aiStartDate.value,
            endDate: aiEndDate.value,
            budget: Number(aiBudget.value),

            // Complete generated itinerary is saved as JSON data.
            itinerary: {
                text: lastGeneratedItinerary
            }
        };

        saveItineraryButton.disabled = true;
        saveItineraryButton.textContent = "Saving Itinerary...";

        try {

            const result = await apiRequest("/api/saved-itineraries", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(requestData)
            });

            showAIStatus(
                `Itinerary saved successfully! Saved ID: ${result.id}`
            );
            
            saveItineraryButton.textContent = "✓ Itinerary Saved";
            await loadSavedItineraries();

        } catch (error) {

            console.error("Save itinerary error:", error);

            showAIStatus(
                `Unable to save itinerary: ${error.message}`,
                true
            );

            saveItineraryButton.disabled = false;
            saveItineraryButton.textContent = "💾 Save Itinerary to My Trips";
        }
    });
}
// ===============================
// SAVED ITINERARIES
// ===============================

async function loadSavedItineraries() {
    const savedList = document.getElementById("saved-itineraries-list");

    if (!savedList) return;

    savedList.textContent = "Loading saved itineraries...";

    try {
        const itineraries = await apiRequest("/api/saved-itineraries");

        savedList.replaceChildren();

        if (itineraries.length === 0) {
            savedList.textContent = "No saved itineraries yet.";
            return;
        }

        itineraries.forEach((item) => {
            const card = document.createElement("div");
            card.className = "saved-itinerary-card";

            const title = document.createElement("h3");
            title.textContent = "🌍 " + item.destination;

            const dates = document.createElement("p");
            dates.textContent =
                "📅 " + item.startDate + " to " + item.endDate;

            const budget = document.createElement("p");
            budget.textContent =
                "💰 Budget: ₹" + Number(item.budget).toLocaleString("en-IN");

            const viewButton = document.createElement("button");
            viewButton.type = "button";
            viewButton.textContent = "View Itinerary";
            // Edit button
const editButton = document.createElement("button");

editButton.type = "button";
editButton.textContent = "✏️ Edit";
editButton.className = "edit-itinerary-btn";

editButton.addEventListener("click", () => {

    editId.value = item.id;
    editDestination.value = item.destination;
    editStartDate.value = item.startDate;
    editEndDate.value = item.endDate;
    editBudget.value = item.budget;

    editModal.hidden = false;
});   
viewButton.addEventListener("click", () => {

    let savedData;

    try {
        savedData = JSON.parse(item.itineraryJson);
    } catch (error) {
        alert("Unable to read saved itinerary.");
        return;
    }

    const newWindow = window.open("", "_blank");

    if (!newWindow) {
        alert("Please allow pop-ups to view your itinerary.");
        return;
    }

    // Dynamic content ko safely HTML mein display karna
    const escapeHTML = (value) => {
        return String(value ?? "").replace(/[&<>"']/g, char => ({
            "&": "&amp;",
            "<": "&lt;",
            ">": "&gt;",
            '"': "&quot;",
            "'": "&#39;"
        })[char]);
    };

    let days = [];
    let budgetItems = [];
    let travelTips = [];
    let demoNotice = "";

    // FORMAT 1: AI-generated text itinerary (date headings + activity labels)
    if (savedData && typeof savedData.text === "string") {

        const lines = savedData.text.split(/\r?\n/);
        let currentDay = null;
        let currentSection = "days";

        lines.forEach(line => {
            const trimmed = line.trim().replace(/^#+\s*/, "").replace(/^\*+|\*+$/g, "").trim();
            if (!trimmed) return;

            if (/^Budget Breakdown\b/i.test(trimmed)) {
                currentSection = "budget";
                currentDay = null;
                return;
            }
            if (/^Travel Tips\b/i.test(trimmed)) {
                currentSection = "tips";
                currentDay = null;
                return;
            }
            if (/^Demo Mode Notice\b/i.test(trimmed)) {
                currentSection = "notice";
                currentDay = null;
                return;
            }

            if (currentSection === "days") {
                // AI output may use a date heading, e.g. 2026-11-15.
                const dateMatch = trimmed.match(/^(\d{4}-\d{2}-\d{2})$/);
                // Also support numbered headings, e.g. Day 1 or Day 1: Jaipur.
                const dayMatch = trimmed.match(/^Day\s+(\d+)\b(?:\s*[:–—-]?\s*(.*))?$/i);

                if (dateMatch || dayMatch) {
                    currentDay = {
                        day: dateMatch ? String(days.length + 1) : dayMatch[1],
                        date: dateMatch ? dateMatch[1] : "",
                        morning: "",
                        afternoon: "",
                        evening: ""
                    };
                    days.push(currentDay);
                    return;
                }

                // Ignore summary lines until the first day heading appears.
                if (!currentDay) return;

                // Supports Morning Activity:, Morning:, etc.
                const activityMatch = trimmed.match(
                    /^(Morning|Afternoon|Evening)(?:\s+Activity)?\s*:\s*(.*)$/i
                );
                if (activityMatch) {
                    currentDay[activityMatch[1].toLowerCase()] = activityMatch[2].trim();
                }
            } else if (currentSection === "budget") {
                const budgetMatch = trimmed.match(/^(.+?):\s*(.+)$/);
                if (budgetMatch && !/^total estimated cost$/i.test(budgetMatch[1])) {
                    budgetItems.push({
                        label: budgetMatch[1],
                        amount: budgetMatch[2]
                    });
                }
            } else if (currentSection === "tips") {
                const tipMatch = trimmed.match(/^\d+[.)]\s*(.+)$/);
                if (tipMatch) {
                    travelTips.push(tipMatch[1]);
                } else if (/^[-•]\s*(.+)$/.test(trimmed)) {
                    travelTips.push(trimmed.replace(/^[-•]\s*/, ""));
                }
            } else if (currentSection === "notice") {
                demoNotice += trimmed + " ";
            }
        });
    }

    // FORMAT 2: Goa - JSON Array format
    else if (Array.isArray(savedData)) {

        days = savedData.map(day => ({
            day: day.day,
            date: day.date || "",
            morning: day.morning || "",
            afternoon: day.afternoon || "",
            evening: day.evening || ""
        }));

    }

    else {
        newWindow.close();
        alert("Unsupported itinerary format.");
        return;
    }

    // Day-wise cards
    const daysHTML = days.map(day => `
        <article class="day-card">

            <h3>Day ${escapeHTML(day.day)}</h3>

            ${day.date
                ? `<p class="day-date">📅 ${escapeHTML(day.date)}</p>`
                : ""}

            ${day.morning
                ? `<div class="activity">
                    <span>🌅 Morning</span>
                    <p>${escapeHTML(day.morning)}</p>
                   </div>`
                : ""}

            ${day.afternoon
                ? `<div class="activity">
                    <span>☀️ Afternoon</span>
                    <p>${escapeHTML(day.afternoon)}</p>
                   </div>`
                : ""}

            ${day.evening
                ? `<div class="activity">
                    <span>🌙 Evening</span>
                    <p>${escapeHTML(day.evening)}</p>
                   </div>`
                : ""}

        </article>
    `).join("");

    // Budget cards
    const budgetHTML = budgetItems.length
        ? `
            <section class="section">
                <h2>💰 Budget Breakdown</h2>

                <div class="budget-grid">
                    ${budgetItems.map(budget => `
                        <div class="budget-card">
                            <h4>${escapeHTML(budget.label)}</h4>
                            <p>${escapeHTML(budget.amount)}</p>
                        </div>
                    `).join("")}
                </div>
            </section>
        `
        : "";

    // Travel tips
    const tipsHTML = travelTips.length
        ? `
            <section class="section">
                <h2>💡 Travel Tips</h2>

                <ul class="tips-list">
                    ${travelTips.map(tip => `
                        <li>${escapeHTML(tip)}</li>
                    `).join("")}
                </ul>
            </section>
        `
        : "";

    const safeDestination = escapeHTML(item.destination);
    const safeStartDate = escapeHTML(item.startDate);
    const safeEndDate = escapeHTML(item.endDate);

    const safeBudget = Number(item.budget).toLocaleString("en-IN");

    const safeNotice = escapeHTML(demoNotice.trim());

    // Professional itinerary page
    newWindow.document.write(`
        <!DOCTYPE html>
        <html lang="en">

        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
             <script src="https://cdnjs.cloudflare.com/ajax/libs/html2pdf.js/0.10.1/html2pdf.bundle.min.js"></script>

            <title>${safeDestination} - TripMate</title>

            <style>

                * {
                    box-sizing: border-box;
                }

                body {
                    margin: 0;
                    padding: 30px 15px;
                    font-family: Arial, sans-serif;
                    background: #f1f5f9;
                    color: #1e293b;
                    line-height: 1.6;
                }

                .container {
                    max-width: 950px;
                    margin: auto;
                    padding: 30px;
                    background: white;
                    border-radius: 16px;
                    box-shadow: 0 5px 25px rgba(0,0,0,0.06);
                }

                .hero {
                    padding: 25px;
                    background: #eff6ff;
                    border-radius: 14px;
                    border-left: 5px solid #2563eb;
                    margin-bottom: 30px;
                }

                h1 {
                    color: #1d4ed8;
                    margin-top: 0;
                }

                h2 {
                    color: #1e40af;
                    margin-top: 30px;
                }

                .summary {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 20px;
                    margin-top: 15px;
                }

                .summary p {
                    margin: 0;
                }

                .total-budget {
                    font-size: 24px;
                    font-weight: bold;
                    color: #15803d;
                }

                .day-card {
                    background: #f8fafc;
                    border: 1px solid #e2e8f0;
                    border-radius: 12px;
                    padding: 22px;
                    margin: 18px 0;
                    break-inside: avoid;
                }

                .day-card h3 {
                    color: #2563eb;
                    margin-top: 0;
                    border-bottom: 1px solid #dbeafe;
                    padding-bottom: 10px;
                }

                .day-date {
                    color: #64748b;
                }

                .activity {
                    background: white;
                    padding: 12px 15px;
                    border-radius: 8px;
                    margin-top: 12px;
                }

                .activity span {
                    font-weight: bold;
                    color: #1e40af;
                }

                .activity p {
                    margin-bottom: 0;
                }

                .budget-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
                    gap: 15px;
                }

                .budget-card {
                    background: #f0fdf4;
                    border: 1px solid #bbf7d0;
                    border-radius: 10px;
                    padding: 15px;
                }

                .budget-card h4 {
                    margin: 0;
                    color: #166534;
                }

                .budget-card p {
                    font-size: 18px;
                    font-weight: bold;
                    margin-bottom: 0;
                }

                .tips-list li {
                    margin-bottom: 12px;
                }

                .demo-notice {
                    background: #fffbeb;
                    border: 1px solid #fde68a;
                    border-radius: 10px;
                    padding: 15px;
                    margin-top: 25px;
                }

                .print-button
                 {
                    background: #2563eb;
                    color: white;
                    border: none;
                    padding: 13px 24px;
                    border-radius: 8px;
                    font-size: 15px;
                    cursor: pointer;
                    margin-top: 25px;
                }
                    .print-button:hover {
    background: #1d4ed8;
}

                .share-button {
    background: #16a34a;
    color: white;
    border: none;
    padding: 13px 24px;
    border-radius: 8px;
    font-size: 15px;
    cursor: pointer;
    margin: 10px 5px;
}

.share-button:hover {
    background: #15803d;
}

.download-button {
    background: #2563eb;
    color: white;
    border: none;
    padding: 13px 24px;
    border-radius: 8px;
    font-size: 15px;
    cursor: pointer;
    margin: 10px 5px;
}
.whatsapp-button {
    background: #25D366;
    color: white;
    border: none;
    padding: 13px 24px;
    border-radius: 8px;
    font-size: 15px;
    cursor: pointer;
    margin: 10px 5px;
}

.whatsapp-button:hover {
    background: #128C7E;
}


.download-button:hover {
    background: #1d4ed8;
}

                .print-button:hover {
                    background: #1d4ed8;
                }

                @media(max-width: 600px) {
                    .container {
                        padding: 18px;
                    }

                    .summary {
                        flex-direction: column;
                        gap: 8px;
                    }
                }

                @media print {
                    body {
                        background: white;
                        padding: 0;
                    }

                    .container {
                        max-width: 100%;
                        box-shadow: none;
                        padding: 10px;
                    }
                        
                    .print-button,
    .share-button,
    .download-button,
    .whatsapp-button {
        display: none !important;
                    }

                    .day-card {
                        break-inside: avoid;
                    }
                }
             
            </style>
        </head>

        <body>

            <main class="container">

                <header class="hero">

                    <h1>✈ TripMate - ${safeDestination}</h1>

                    <p>Your Saved Travel Itinerary</p>

                    <div class="summary">

                        <p>📅 ${safeStartDate} to ${safeEndDate}</p>

                        <p class="total-budget">
                            💰 Budget: ₹${safeBudget}
                        </p>

                    </div>

                </header>

                <section class="section">

                    <h2>🗺️ Day-wise Travel Plan</h2>

                    ${daysHTML || "<p>No day-wise activities available.</p>"}

                </section>

                ${budgetHTML}

                ${tipsHTML}

                ${safeNotice
                    ? `<div class="demo-notice">
                        <strong>ℹ️ Demo Mode Notice</strong>
                        <p>${safeNotice}</p>
                       </div>`
                    : ""}
                   <button type="button" id="share-itinerary-button" class="share-button">
                    📤 Share Itinerary
                   </button>
                 <button type="button" id="download-pdf-button" class="download-button">
                 📥 Download PDF
                 </button>
                 <button type="button" id="whatsapp-share-button" class="whatsapp-button">
    💬 Share on WhatsApp
</button>
                <button class="print-button" onclick="window.print()">
                    🖨 Print Itinerary
                </button>

            </main>
           <script>
    // Download PDF
    function downloadItineraryPDF() {
        const element = document.querySelector("main");

        if (typeof html2pdf === "undefined") {
            alert("PDF library load nahi hui. Internet check karein.");
            return;
        }

        const options = {
            margin: 10,
            filename: "TripMate-Itinerary.pdf",
            image: {
                type: "jpeg",
                quality: 0.98
            },
            html2canvas: {
                scale: 2,
                useCORS: true
            },
            jsPDF: {
                unit: "mm",
                format: "a4",
                orientation: "portrait"
            }
        };

        html2pdf().set(options).from(element).save();
    }

    // Share Itinerary
async function shareItinerary() {
    const shareText =
        "TripMate - My Travel Itinerary\\n\\n" +
        document.querySelector("main").innerText;

    if (navigator.share) {
        try {
            await navigator.share({
                title: "My TripMate Itinerary",
                text: shareText
            });
        } catch (error) {
            if (error.name !== "AbortError") {
                alert("Unable to share itinerary.");
                console.error(error);
            }
        }
    } else {
        try {
            await navigator.clipboard.writeText(shareText);
            alert("Itinerary copied! Now share it on WhatsApp.");
        } catch (error) {
            alert("Unable to copy itinerary.");
            console.error(error);
        }
    }
}
// Share itinerary on WhatsApp
function shareOnWhatsApp() {
    const shareText =
       "TripMate - My Travel Itinerary" + String.fromCharCode(10, 10) +
        document.querySelector("main").innerText;

    const whatsappURL =
        "https://wa.me/?text=" + encodeURIComponent(shareText);

    window.open(whatsappURL, "_blank");
}
// Connect buttons with functions
const shareButton = document.getElementById("share-itinerary-button");
const downloadButton = document.getElementById("download-pdf-button");
const whatsappButton =
    document.getElementById("whatsapp-share-button");

if (shareButton) {
    shareButton.addEventListener("click", shareItinerary);
}

if (downloadButton) {
    downloadButton.addEventListener("click", downloadItineraryPDF);
}
    if (whatsappButton) {
    whatsappButton.addEventListener("click", shareOnWhatsApp);
}

console.log("TripMate buttons connected successfully!");
</script>

        </body>
        </html>
    `);

    newWindow.document.close();

});
            // Delete button
            const deleteButton = document.createElement("button");
            deleteButton.type = "button";
            deleteButton.textContent = "🗑 Delete";
            deleteButton.className = "delete-itinerary-btn";

            deleteButton.addEventListener("click", async () => {

                const confirmDelete = confirm(
                    `Are you sure you want to delete the ${item.destination} itinerary?`
                );

                if (!confirmDelete) {
                    return;
                }

                try {
                    deleteButton.disabled = true;
                    deleteButton.textContent = "Deleting...";

                    const deleteResponse = await fetch(
                        `/api/saved-itineraries/${item.id}`,
                        {
                            method: "DELETE",
                            credentials: "same-origin"
                        }
                    );

                    if (!deleteResponse.ok) {
                        throw new Error("Unable to delete itinerary.");
                    }

                    alert("Itinerary deleted successfully!");

                    await loadSavedItineraries();

                } catch (error) {
                    console.error("Delete error:", error);
                    alert(error.message);

                    deleteButton.disabled = false;
                    deleteButton.textContent = "🗑 Delete";
                }
            });
card.appendChild(title);
card.appendChild(dates);
card.appendChild(budget);
card.appendChild(viewButton);
card.appendChild(editButton);
card.appendChild(deleteButton);

savedList.appendChild(card);
        });

    } catch (error) {
        console.error(error);
        savedList.textContent = "Error loading saved itineraries.";
    }
}
// Open and close Edit Modal

cancelItineraryModalButton.addEventListener("click", () => {
    editModal.hidden = true;
    editForm.reset();
});

// Update Saved Itinerary

editForm.addEventListener("submit", async (event) => {
    event.preventDefault();

    const id = editId.value;
    const destination = editDestination.value.trim();
    const startDate = editStartDate.value;
    const endDate = editEndDate.value;
    const budget = Number(editBudget.value);

    if (!destination) {
        alert("Destination is required.");
        return;
    }

    if (!startDate || !endDate || endDate < startDate) {
        alert("Please select valid travel dates.");
        return;
    }

    if (!Number.isFinite(budget) || budget <= 0) {
        alert("Please enter a valid budget.");
        return;
    }

    updateItineraryButton.disabled = true;
    updateItineraryButton.textContent = "Updating...";

    try {
        const savedItem = await apiRequest(
            `/api/saved-itineraries/${id}`
        );

        const requestData = {
            destination,
            startDate,
            endDate,
            budget,
            itinerary: JSON.parse(savedItem.itineraryJson)
        };

        await apiRequest(`/api/saved-itineraries/${id}`, {
            method: "PUT",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(requestData)
        });

        alert("Itinerary updated successfully!");

        editModal.hidden = true;
        editForm.reset();

        await loadSavedItineraries();

    } catch (error) {
        console.error("Update error:", error);
        alert(`Unable to update itinerary: ${error.message}`);

    } finally {
        updateItineraryButton.disabled = false;
        updateItineraryButton.textContent = "💾 Update Itinerary";
    }
});

