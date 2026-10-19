const API_BASE = "http://localhost:8080/api";

async function apiRequest(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    ...options
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Backend request failed");
  return data;
}

function showBackendStatus(connected, message) {
  let status = document.querySelector("#backend-status");
  if (!status) {
    status = document.createElement("div");
    status.id = "backend-status";
    document.body.appendChild(status);
  }
  status.textContent = message;
  status.style.cssText = `position:fixed;right:18px;bottom:18px;z-index:9999;padding:10px 14px;border-radius:8px;color:white;font:600 13px Arial;background:${connected ? "#059669" : "#dc2626"}`;
}

async function checkBackend() {
  try {
    await apiRequest("/health", { headers: {} });
    showBackendStatus(true, "Backend connected");
  } catch (_) {
    showBackendStatus(false, "Backend offline - start BackendServer");
  }
}

async function saveStudent() {
  const form = document.querySelector("#modal-add-student form");
  if (!form) return;
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const fields = form.querySelectorAll("input, select");
    const body = new URLSearchParams({
      id: fields[0].value,
      name: fields[1].value,
      stop: fields[2].value,
      authorized: form.querySelector('input[name="stAuth"]:checked')?.value || "false"
    });
    try {
      await apiRequest("/students", { method: "POST", body });
      showBackendStatus(true, "Student saved to backend");
      window.location.hash = "view-students";
    } catch (error) {
      showBackendStatus(false, error.message);
    }
  });
}


async function loadStudents() {
  try {
    const students = await apiRequest("/students", {
      headers: {}
    });

    const tbody = document.querySelector("#student-table-body");
    if (!tbody) {
      console.error("Student table body not found");
      return;
    }

    // Remove the 40 static sample rows
    tbody.replaceChildren();

    // Display students received from the database
    students.forEach(student => {
      const row = document.createElement("tr");

      const values = [
        "#" + student.id,
        student.name,
        student.stop,
        student.authorized ? "Authorized" : "Unauthorized",
        "Unallocated",
        "Not updated",
        "Reassign"
      ];

      values.forEach((value, index) => {
        const cell = document.createElement("td");

        if (index === 0) {
          const strong = document.createElement("strong");
          strong.textContent = value;
          cell.appendChild(strong);
        } else if (index === 3) {
          const badge = document.createElement("span");
          badge.className = student.authorized
            ? "badge badge-success"
            : "badge badge-danger";
          badge.textContent = value;
          cell.appendChild(badge);
        } else if (index === 4) {
          const badge = document.createElement("span");
          badge.className = "badge badge-warning";
          badge.textContent = value;
          cell.appendChild(badge);
        } else if (index === 5) {
          const badge = document.createElement("span");
          badge.className = "badge badge-warning";
          badge.textContent = value;
          cell.appendChild(badge);
        } else if (index === 6) {
          const link = document.createElement("a");
          link.href = "#modal-edit-capacity";
          link.className = "btn-table-action";
          link.textContent = value;
          cell.appendChild(link);
        } else {
          cell.textContent = value;
        }

        row.appendChild(cell);
      });

      tbody.appendChild(row);
    });

    console.log("Student directory updated:", students.length);
  } catch (error) {
    console.error("Failed to load students:", error);
    showBackendStatus(false, "Failed to load students");
  }
}

async function loadAttendance(date) {
  const dateInput = document.querySelector("#attendanceDatePicker");
  const selectedDate = date || (dateInput && dateInput.value ? dateInput.value : new Date().toISOString().split("T")[0]);
  if (dateInput && !dateInput.value) {
    dateInput.value = selectedDate;
  }

  try {
    const records = await apiRequest(`/attendance?date=${encodeURIComponent(selectedDate)}`, {
      headers: {}
    });

    const tbody = document.querySelector("#attendance-table-body");
    if (!tbody) {
      console.error("Attendance table body not found");
      return;
    }

    tbody.replaceChildren();

    records.forEach(student => {
      const row = document.createElement("tr");

      // 1. Student ID
      const idCell = document.createElement("td");
      const strongId = document.createElement("strong");
      strongId.textContent = "#" + student.id;
      idCell.appendChild(strongId);
      row.appendChild(idCell);

      // 2. Full Name
      const nameCell = document.createElement("td");
      nameCell.textContent = student.name;
      row.appendChild(nameCell);

      // 3. Vehicle / Bus
      const busCell = document.createElement("td");
      const busBadge = document.createElement("span");
      const isAllocated = student.bus && student.bus !== "Unallocated";
      busBadge.className = isAllocated ? "badge badge-info" : "badge badge-neutral";
      busBadge.textContent = isAllocated ? student.bus : "Unallocated";
      busCell.appendChild(busBadge);
      row.appendChild(busCell);

      // 4. Pickup Stop
      const stopCell = document.createElement("td");
      stopCell.textContent = student.stop || "Not set";
      row.appendChild(stopCell);

      // 5. Campus Attendance (Present / Absent)
      const attCell = document.createElement("td");
      const attSelect = document.createElement("select");
      attSelect.className = "form-input";
      attSelect.style.cssText = "padding:3px 8px;font-size:12px;font-weight:600;border-radius:4px;width:auto;cursor:pointer;";

      const optPresent = document.createElement("option");
      optPresent.value = "Present";
      optPresent.textContent = "Present";

      const optAbsent = document.createElement("option");
      optAbsent.value = "Absent";
      optAbsent.textContent = "Absent";

      const isAbsent = student.status && student.status.toLowerCase() === "absent";
      if (isAbsent) {
        optAbsent.selected = true;
        attSelect.style.color = "#dc2626";
      } else {
        optPresent.selected = true;
        attSelect.style.color = "#059669";
      }

      attSelect.appendChild(optPresent);
      attSelect.appendChild(optAbsent);
      attSelect.addEventListener("change", () => {
        attSelect.style.color = attSelect.value === "Absent" ? "#dc2626" : "#059669";
      });
      attCell.appendChild(attSelect);
      row.appendChild(attCell);

      // 6. Safe Boarding Status (Boarded / Pending)
      const boardCell = document.createElement("td");
      const boardSelect = document.createElement("select");
      boardSelect.className = "form-input";
      boardSelect.style.cssText = "padding:3px 8px;font-size:12px;font-weight:600;border-radius:4px;width:auto;cursor:pointer;";

      const optBoarded = document.createElement("option");
      optBoarded.value = "true";
      optBoarded.textContent = "Boarded";

      const optPending = document.createElement("option");
      optPending.value = "false";
      optPending.textContent = "Pending / Not Boarded";

      if (student.boarded) {
        optBoarded.selected = true;
        boardSelect.style.color = "#059669";
      } else {
        optPending.selected = true;
        boardSelect.style.color = "#d97706";
      }

      boardSelect.appendChild(optBoarded);
      boardSelect.appendChild(optPending);
      boardSelect.addEventListener("change", () => {
        boardSelect.style.color = boardSelect.value === "true" ? "#059669" : "#d97706";
      });
      boardCell.appendChild(boardSelect);
      row.appendChild(boardCell);

      // 7. Action: Save to MySQL Button
      const actionCell = document.createElement("td");
      const saveBtn = document.createElement("button");
      saveBtn.type = "button";
      saveBtn.className = "btn-table-action";
      saveBtn.textContent = "Save";
      saveBtn.style.cursor = "pointer";

      saveBtn.addEventListener("click", async () => {
        saveBtn.disabled = true;
        saveBtn.textContent = "Saving...";
        try {
          const newStatus = attSelect.value;
          const newBoarded = boardSelect.value === "true";
          const currentDate = dateInput && dateInput.value ? dateInput.value : selectedDate;

          await saveAttendance(student.id, currentDate, newStatus, newBoarded);
          saveBtn.textContent = "Saved ✓";
          saveBtn.style.backgroundColor = "#dcfce7";
          saveBtn.style.color = "#15803d";
          setTimeout(() => {
            saveBtn.textContent = "Save";
            saveBtn.style.backgroundColor = "";
            saveBtn.style.color = "";
            saveBtn.disabled = false;
          }, 1800);
        } catch (err) {
          saveBtn.textContent = "Failed";
          saveBtn.style.backgroundColor = "#fee2e2";
          saveBtn.style.color = "#b91c1c";
          setTimeout(() => {
            saveBtn.textContent = "Save";
            saveBtn.style.backgroundColor = "";
            saveBtn.style.color = "";
            saveBtn.disabled = false;
          }, 2000);
        }
      });

      actionCell.appendChild(saveBtn);
      row.appendChild(actionCell);

      tbody.appendChild(row);
    });

    console.log(`Daily Attendance loaded for ${selectedDate}: ${records.length} records`);
  } catch (error) {
    console.error("Failed to load attendance:", error);
    showBackendStatus(false, "Failed to load attendance from database");
  }
}

async function saveAttendance(studentId, date, status, boarded) {
  const body = new URLSearchParams({
    id: studentId,
    date: date,
    status: status,
    boarded: boarded ? "true" : "false"
  });

  try {
    const data = await apiRequest("/attendance", {
      method: "POST",
      body: body
    });
    showBackendStatus(true, `Attendance saved for Student #${studentId} (${status})`);
    return data;
  } catch (error) {
    showBackendStatus(false, "Failed to save attendance: " + error.message);
    throw error;
  }
}

function initAttendanceModule() {
  const dateInput = document.querySelector("#attendanceDatePicker");
  if (dateInput) {
    if (!dateInput.value) {
      dateInput.value = new Date().toISOString().split("T")[0];
    }
    dateInput.addEventListener("change", (e) => {
      loadAttendance(e.target.value);
    });
  }
  loadAttendance();

  window.addEventListener("hashchange", () => {
    if (window.location.hash === "#view-attendance") {
      const d = document.querySelector("#attendanceDatePicker");
      loadAttendance(d ? d.value : undefined);
    } else if (window.location.hash === "#view-buses") {
      loadBuses();
    } else if (window.location.hash === "#view-routes") {
      loadRoutes();
    } else if (window.location.hash === "#view-fees") {
      loadFees();
    }
  });
}

function escapeHtml(text) {
  if (!text) return "";
  const div = document.createElement("div");
  div.textContent = String(text);
  return div.innerHTML;
}

async function loadBuses() {
  try {
    const buses = await apiRequest("/buses", { headers: {} });
    const container = document.querySelector("#bus-cards-container");
    if (container) {
      container.replaceChildren();

      buses.forEach(bus => {
        const card = document.createElement("div");
        const isStandby = bus.type === "Emergency Standby" || bus.id === 4;
        card.className = "bus-card" + (isStandby ? " border-accent-highlight" : "");

        const pct = bus.occupancyPercent || 0;
        let barColor = "bg-blue";
        if (bus.id === 2) barColor = "bg-indigo";
        else if (bus.id === 3) barColor = "bg-emerald";
        else if (isStandby) barColor = "bg-emerald";

        let statusBadgeClass = "badge-success";
        if (bus.status === "Breakdown Reported") statusBadgeClass = "badge-danger";
        else if (bus.status === "Deployed (Relief)") statusBadgeClass = "badge-warning";
        else if (isStandby && (bus.status === "Standby Depot" || !bus.status)) statusBadgeClass = "badge-neutral";

        card.innerHTML = `
          <div class="bus-card-header ${isStandby ? "bg-slate-soft" : ""}">
            <div>
              <span class="bus-type-badge ${isStandby ? "bg-amber-soft text-amber" : ""}">${escapeHtml(bus.type || "Standard Bus")}</span>
              <h3 class="bus-card-title">${escapeHtml(bus.name)}${bus.route ? " &bull; " + escapeHtml(bus.route) : ""}</h3>
              <span class="bus-card-reg">Reg: ${escapeHtml(bus.number || "TN-09-CB-" + (100 + bus.id))}</span>
            </div>
            <span class="badge ${statusBadgeClass}">${escapeHtml(bus.status || "Active")}</span>
          </div>
          <div class="bus-card-body">
            <div class="bus-spec-row">
              <span>Assigned Driver:</span>
              <strong>${escapeHtml(bus.driver || "Unassigned")} ${bus.phone ? "(Ph: " + escapeHtml(bus.phone) + ")" : ""}</strong>
            </div>
            <div class="bus-spec-row">
              <span>Corridor Stops:</span>
              <span>${escapeHtml(bus.stops || "Designated corridor pickup stops")}</span>
            </div>
            <div class="bus-capacity-bar-wrapper">
              <div class="bus-cap-label">
                <span>Seating Occupancy</span>
                <strong>${bus.assigned} / ${bus.capacity} Assigned (${pct}%)</strong>
              </div>
              <div class="progress-bar">
                <div class="progress-fill ${barColor}" style="width: ${Math.min(pct, 100)}%;"></div>
              </div>
              <small class="${bus.available > 0 ? "text-emerald" : "text-danger"}">
                <strong>${bus.available} Seats Available</strong> &mdash; ${bus.available > 0 ? "Safe allocation margin" : "Vehicle at full capacity"}
              </small>
            </div>
          </div>
          <div class="bus-card-footer">
            ${isStandby ?
              `<a href="#modal-breakdown-wizard" class="btn btn-danger btn-sm w-full">Deploy Mini Bus Shuttle</a>` :
              `<a href="#view-driver-${bus.id}" class="btn btn-outline btn-sm">View Driver ${bus.id} Run Sheet</a>
               <a href="#modal-edit-capacity" class="btn btn-primary btn-sm btn-change-capacity" data-bus-id="${bus.id}" data-bus-cap="${bus.capacity}">Change Capacity</a>`
            }
          </div>
        `;
        container.appendChild(card);
      });

      container.querySelectorAll(".btn-change-capacity").forEach(btn => {
        btn.addEventListener("click", () => {
          const busId = btn.getAttribute("data-bus-id");
          const sel = document.querySelector("#select-bus-capacity");
          if (sel) sel.value = busId;
          const inp = document.querySelector("#input-bus-capacity");
          if (inp) inp.value = btn.getAttribute("data-bus-cap");
        });
      });
    }

    const select = document.querySelector("#select-bus-capacity");
    if (select) {
      const currentVal = select.value;
      select.replaceChildren();
      buses.filter(b => b.type !== "Emergency Standby").forEach(b => {
        const opt = document.createElement("option");
        opt.value = b.id;
        opt.textContent = `${b.name} — Currently ${b.assigned} Students Assigned (Cap: ${b.capacity})`;
        select.appendChild(opt);
      });
      if (currentVal) select.value = currentVal;
    }

    console.log("Bus fleet loaded from database:", buses.length);
  } catch (err) {
    console.error("Failed to load buses:", err);
    showBackendStatus(false, "Failed to load bus fleet from database");
  }
}

async function saveBusCapacity(busId, newCapacity) {
  const body = new URLSearchParams({
    id: busId,
    capacity: newCapacity
  });

  try {
    const data = await apiRequest("/buses", {
      method: "POST",
      body: body
    });
    showBackendStatus(true, `Bus #${busId} capacity updated to ${newCapacity}`);
    await loadBuses();
    window.location.hash = "view-buses";
    return data;
  } catch (err) {
    showBackendStatus(false, "Failed to update capacity: " + err.message);
    throw err;
  }
}

function initBusCapacityForm() {
  const form = document.querySelector("#form-edit-capacity");
  if (!form) return;
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const sel = document.querySelector("#select-bus-capacity");
    const inp = document.querySelector("#input-bus-capacity");
    if (!sel || !inp) return;
    try {
      await saveBusCapacity(sel.value, inp.value);
    } catch (_) {}
  });
}

async function loadRoutes() {
  try {
    const routes = await apiRequest("/routes", { headers: {} });
    const container = document.querySelector("#route-cards-container");
    if (container) {
      container.replaceChildren();

      routes.forEach((route, idx) => {
        const card = document.createElement("div");
        card.className = "route-card";

        const colorClasses = [
          { header: "bg-blue-soft", tag: "text-blue" },
          { header: "bg-indigo-soft", tag: "text-indigo" },
          { header: "bg-emerald-soft", tag: "text-emerald" }
        ];
        const c = colorClasses[idx % colorClasses.length];

        let stopsHtml = "";
        if (route.stops && route.stops.length > 0) {
          stopsHtml = route.stops.map(st => `
            <div class="route-stop-item">
              <span class="stop-dot"></span>
              <div class="stop-info">
                <strong>${st.order}. ${escapeHtml(st.name)}</strong>
                <span>Scheduled Time: ${escapeHtml(st.time || "Regular")} &bull; Road: ${escapeHtml(st.road || "Standard")}</span>
              </div>
            </div>
          `).join("");
        } else {
          stopsHtml = `<p class="text-muted p-3">No stops configured for this corridor.</p>`;
        }

        card.innerHTML = `
          <div class="route-card-header ${c.header}">
            <span class="route-tag ${c.tag}">${escapeHtml(route.name)}${route.corridor ? " &bull; " + escapeHtml(route.corridor) : ""}</span>
            <h4>${escapeHtml(route.busName || "Bus " + route.busId)}${route.busNumber ? " &bull; " + escapeHtml(route.busNumber) : ""}</h4>
          </div>
          <div class="route-stops-list">
            ${stopsHtml}
          </div>
        `;

        container.appendChild(card);
      });
    }

    console.log("Route corridors loaded from database:", routes.length);
  } catch (err) {
    console.error("Failed to load routes:", err);
    showBackendStatus(false, "Failed to load route corridors from database");
  }
}

async function loadFees() {
  try {
    const fees = await apiRequest("/fees", { headers: {} });
    const tbody = document.querySelector("#fee-table-body");
    if (!tbody) {
      console.error("Fee table body not found");
      return;
    }

    // Calculate metrics
    let paidCount = 0;
    fees.forEach(f => {
      if (f.status && f.status.toLowerCase() === "paid") {
        paidCount++;
      }
    });
    const totalCount = fees.length;
    const unpaidCount = totalCount - paidCount;
    const paidTotal = paidCount * 12000;
    const pendingTotal = unpaidCount * 12000;

    // Update top metric cards
    const elPaidCount = document.querySelector("#fee-metric-paid-count");
    if (elPaidCount) elPaidCount.textContent = `${paidCount} / ${totalCount}`;
    const elPaidTotal = document.querySelector("#fee-metric-paid-total");
    if (elPaidTotal) elPaidTotal.textContent = `₹${paidTotal.toLocaleString('en-IN')} Collected`;

    const elPendingCount = document.querySelector("#fee-metric-pending-count");
    if (elPendingCount) elPendingCount.textContent = `${unpaidCount} / ${totalCount}`;
    const elPendingTotal = document.querySelector("#fee-metric-pending-total");
    if (elPendingTotal) elPendingTotal.textContent = `₹${pendingTotal.toLocaleString('en-IN')} Pending`;

    tbody.replaceChildren();

    fees.forEach(rec => {
      const row = document.createElement("tr");

      // 1. Student ID
      const idTd = document.createElement("td");
      const strongId = document.createElement("strong");
      strongId.textContent = "#" + rec.studentId;
      idTd.appendChild(strongId);
      row.appendChild(idTd);

      // 2. Name
      const nameTd = document.createElement("td");
      nameTd.textContent = rec.name;
      row.appendChild(nameTd);

      // 3. Assigned Bus
      const busTd = document.createElement("td");
      const busBadge = document.createElement("span");
      const isAllocated = rec.bus && rec.bus !== "Unallocated";
      busBadge.className = isAllocated ? "badge badge-info" : "badge badge-neutral";
      busBadge.textContent = isAllocated ? rec.bus : "Unallocated";
      busTd.appendChild(busBadge);
      row.appendChild(busTd);

      // 4. Fee Amount
      const amtTd = document.createElement("td");
      amtTd.textContent = "₹" + Number(rec.amount || 12000).toLocaleString('en-IN');
      row.appendChild(amtTd);

      // 5. Payment Status Selector (Paid / Unpaid)
      const statusTd = document.createElement("td");
      const statusSelect = document.createElement("select");
      statusSelect.className = "form-input";
      statusSelect.style.cssText = "padding:3px 8px;font-size:12px;font-weight:600;border-radius:4px;width:auto;cursor:pointer;";

      const optPaid = document.createElement("option");
      optPaid.value = "Paid";
      optPaid.textContent = "Paid";

      const optUnpaid = document.createElement("option");
      optUnpaid.value = "Unpaid";
      optUnpaid.textContent = "Unpaid";

      const isPaid = rec.status && rec.status.toLowerCase() === "paid";
      if (isPaid) {
        optPaid.selected = true;
        statusSelect.style.color = "#059669";
      } else {
        optUnpaid.selected = true;
        statusSelect.style.color = "#dc2626";
      }

      statusSelect.appendChild(optPaid);
      statusSelect.appendChild(optUnpaid);
      statusTd.appendChild(statusSelect);
      row.appendChild(statusTd);

      // 6. Receipt Date
      const dateTd = document.createElement("td");
      const dateInput = document.createElement("input");
      dateInput.type = "date";
      dateInput.className = "form-input";
      dateInput.style.cssText = "padding:2px 6px;font-size:12px;width:auto;";
      if (rec.date) {
        dateInput.value = rec.date;
      } else if (isPaid) {
        dateInput.value = new Date().toISOString().split("T")[0];
      }
      dateTd.appendChild(dateInput);
      row.appendChild(dateTd);

      // 7. Pass Clearance Badge
      const passTd = document.createElement("td");
      const passBadge = document.createElement("span");
      passBadge.className = isPaid ? "badge badge-success" : "badge badge-danger";
      passBadge.textContent = isPaid ? "Cleared" : "Hold";
      passTd.appendChild(passBadge);
      row.appendChild(passTd);

      // Synchronize badge on status select change
      statusSelect.addEventListener("change", () => {
        const nowPaid = statusSelect.value === "Paid";
        statusSelect.style.color = nowPaid ? "#059669" : "#dc2626";
        passBadge.className = nowPaid ? "badge badge-success" : "badge badge-danger";
        passBadge.textContent = nowPaid ? "Cleared" : "Hold";
        if (nowPaid && !dateInput.value) {
          dateInput.value = new Date().toISOString().split("T")[0];
        }
      });

      // 8. Action: Save Button
      const actionTd = document.createElement("td");
      const saveBtn = document.createElement("button");
      saveBtn.type = "button";
      saveBtn.className = "btn-table-action";
      saveBtn.textContent = "Save";
      saveBtn.style.cursor = "pointer";

      saveBtn.addEventListener("click", async () => {
        saveBtn.disabled = true;
        saveBtn.textContent = "Saving...";
        try {
          const selectedStatus = statusSelect.value;
          const selectedDate = dateInput.value;
          await saveFeeStatus(rec.studentId, rec.amount || 12000, selectedStatus, selectedDate);
          saveBtn.textContent = "Saved ✓";
          saveBtn.style.backgroundColor = "#dcfce7";
          saveBtn.style.color = "#15803d";
          setTimeout(() => {
            saveBtn.textContent = "Save";
            saveBtn.style.backgroundColor = "";
            saveBtn.style.color = "";
            saveBtn.disabled = false;
          }, 1800);
        } catch (err) {
          saveBtn.textContent = "Failed";
          saveBtn.style.backgroundColor = "#fee2e2";
          saveBtn.style.color = "#b91c1c";
          setTimeout(() => {
            saveBtn.textContent = "Save";
            saveBtn.style.backgroundColor = "";
            saveBtn.style.color = "";
            saveBtn.disabled = false;
          }, 2000);
        }
      });

      actionTd.appendChild(saveBtn);
      row.appendChild(actionTd);

      tbody.appendChild(row);
    });

    console.log("Fee collection manifest loaded from database:", fees.length);
  } catch (err) {
    console.error("Failed to load fees:", err);
    showBackendStatus(false, "Failed to load fee ledger from database");
  }
}

async function saveFeeStatus(studentId, amount, status, date) {
  const body = new URLSearchParams({
    id: studentId,
    amount: amount,
    status: status,
    date: date || ""
  });

  try {
    const data = await apiRequest("/fees", {
      method: "POST",
      body: body
    });
    showBackendStatus(true, `Fee status updated for Student #${studentId} (${status})`);
    await loadFees();
    // Also refresh Student Directory so transport pass badges stay in sync
    loadStudents();
    return data;
  } catch (err) {
    showBackendStatus(false, "Failed to save fee status: " + err.message);
    throw err;
  }
}

// ===================================================================
// MODULE 5: TOMORROW'S DEMAND & STUDENT TRAVEL DECLARATIONS
// ===================================================================

function getTomorrowDateString() {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  return tomorrow.toISOString().split("T")[0];
}

async function loadRequirements(selectedDate) {
  const dateInput = document.querySelector("#requirementsDatePicker");
  const dateStr = selectedDate || (dateInput && dateInput.value) || getTomorrowDateString();

  if (dateInput && !dateInput.value) {
    dateInput.value = dateStr;
  }

  try {
    const list = await apiRequest(`/requirements?date=${encodeURIComponent(dateStr)}`);
    const tbody = document.querySelector("#requirements-table-body");
    const title = document.querySelector("#requirements-card-title");

    if (!Array.isArray(list)) return;

    const requiredCount = list.filter(item => item.busRequired).length;
    if (title) {
      title.textContent = `Declared Intentions (${requiredCount} Students Required for ${dateStr})`;
    }

    if (tbody) {
      tbody.replaceChildren();

      if (list.length === 0) {
        const row = document.createElement("tr");
        row.innerHTML = `<td colspan="6" style="text-align:center;color:var(--slate-500);padding:24px;">No students registered in the system yet.</td>`;
        tbody.appendChild(row);
        return;
      }

      list.forEach(item => {
        const row = document.createElement("tr");

        // 1. Student ID
        const idTd = document.createElement("td");
        idTd.textContent = `#${item.id}`;
        row.appendChild(idTd);

        // 2. Name
        const nameTd = document.createElement("td");
        nameTd.textContent = item.name;
        row.appendChild(nameTd);

        // 3. Assigned Bus
        const busTd = document.createElement("td");
        busTd.textContent = item.bus || "Unallocated";
        row.appendChild(busTd);

        // 4. Pickup Stop
        const stopTd = document.createElement("td");
        stopTd.textContent = item.stop || "Not Specified";
        row.appendChild(stopTd);

        // 5. Bus Required Tomorrow Status / Selector
        const statusTd = document.createElement("td");
        const selectStatus = document.createElement("select");
        selectStatus.className = "form-select";
        selectStatus.style.padding = "4px 8px";
        selectStatus.style.fontSize = "13px";
        selectStatus.style.width = "auto";
        selectStatus.style.borderRadius = "6px";

        const optYes = document.createElement("option");
        optYes.value = "true";
        optYes.textContent = "Yes (Required)";
        if (item.busRequired) optYes.selected = true;

        const optNo = document.createElement("option");
        optNo.value = "false";
        optNo.textContent = "No (Self Commute)";
        if (!item.busRequired) optNo.selected = true;

        selectStatus.appendChild(optYes);
        selectStatus.appendChild(optNo);

        const badge = document.createElement("span");
        badge.className = item.busRequired ? "badge badge-success ml-2" : "badge badge-neutral ml-2";
        badge.textContent = item.busRequired ? "Required" : "Self Commute";

        selectStatus.addEventListener("change", () => {
          const isReq = selectStatus.value === "true";
          badge.className = isReq ? "badge badge-success ml-2" : "badge badge-neutral ml-2";
          badge.textContent = isReq ? "Required" : "Self Commute";
        });

        statusTd.appendChild(selectStatus);
        statusTd.appendChild(badge);
        row.appendChild(statusTd);

        // 6. Action: Save button
        const actionTd = document.createElement("td");
        const saveBtn = document.createElement("button");
        saveBtn.type = "button";
        saveBtn.className = "btn-table-action";
        saveBtn.textContent = "Save";
        saveBtn.style.cursor = "pointer";

        saveBtn.addEventListener("click", async () => {
          saveBtn.disabled = true;
          saveBtn.textContent = "Saving...";
          try {
            const reqVal = selectStatus.value === "true";
            await saveRequirement(item.id, dateStr, reqVal);
            saveBtn.textContent = "Saved ✓";
            saveBtn.style.backgroundColor = "#dcfce7";
            saveBtn.style.color = "#15803d";
            setTimeout(() => {
              saveBtn.textContent = "Save";
              saveBtn.style.backgroundColor = "";
              saveBtn.style.color = "";
              saveBtn.disabled = false;
            }, 1800);
          } catch (err) {
            saveBtn.textContent = "Failed";
            saveBtn.style.backgroundColor = "#fee2e2";
            saveBtn.style.color = "#b91c1c";
            setTimeout(() => {
              saveBtn.textContent = "Save";
              saveBtn.style.backgroundColor = "";
              saveBtn.style.color = "";
              saveBtn.disabled = false;
            }, 2000);
          }
        });

        actionTd.appendChild(saveBtn);
        row.appendChild(actionTd);

        tbody.appendChild(row);
      });
    }

    console.log("Tomorrow demand requirements loaded from database:", list.length);
  } catch (err) {
    console.error("Failed to load tomorrow requirements:", err);
    showBackendStatus(false, "Failed to load tomorrow demand from database");
  }
}

async function saveRequirement(studentId, dateStr, busRequired) {
  const body = new URLSearchParams({
    id: studentId,
    date: dateStr,
    required: busRequired ? "true" : "false"
  });

  try {
    const data = await apiRequest("/requirements", {
      method: "POST",
      body: body
    });
    showBackendStatus(true, `Demand updated for Student #${studentId} on ${dateStr}`);
    await loadRequirements(dateStr);
    return data;
  } catch (err) {
    showBackendStatus(false, "Failed to save requirement: " + err.message);
    throw err;
  }
}

function initRequirementsModule() {
  const dateInput = document.querySelector("#requirementsDatePicker");
  if (dateInput) {
    dateInput.value = getTomorrowDateString();
    dateInput.addEventListener("change", () => {
      loadRequirements(dateInput.value);
    });
  }

  // Hook up student portal intention declaration form
  const studentForm = document.querySelector("#form-student-intention");
  if (studentForm) {
    studentForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const saveBtn = document.querySelector("#btn-save-student-intention");
      const selectedRadio = studentForm.querySelector('input[name="tomorrowReq"]:checked');
      const isReq = selectedRadio ? selectedRadio.value === "yes" : true;
      const tomorrowStr = getTomorrowDateString();

      // Find the active student ID
      let activeStudentId = 223;
      try {
        const studentList = await apiRequest("/students");
        if (Array.isArray(studentList) && studentList.length > 0) {
          const match = studentList.find(s => s.id === 101) || studentList[0];
          activeStudentId = match.id;
        }
      } catch (e) {
        console.warn("Could not fetch active student id, defaulting to 223");
      }

      if (saveBtn) {
        saveBtn.disabled = true;
        saveBtn.textContent = "Saving...";
      }

      try {
        await saveRequirement(activeStudentId, tomorrowStr, isReq);

        const banner = document.querySelector("#banner-student-intention");
        const statusText = document.querySelector("#status-student-intention-text");
        const descText = document.querySelector("#desc-student-intention-text");

        if (statusText) {
          statusText.textContent = isReq ? "Current Status: Required (Yes)" : "Current Status: Self-Commuting (No)";
        }
        if (descText) {
          descText.textContent = isReq
            ? "Your seat is reserved for tomorrow morning transit operations."
            : "Seat freed up for emergency standby buffer. Self-commute declared.";
        }
        if (banner) {
          banner.className = isReq ? "alert-banner alert-success mt-4" : "alert-banner alert-warning mt-4";
        }

        if (saveBtn) {
          saveBtn.textContent = "Saved Successfully ✓";
          setTimeout(() => {
            saveBtn.textContent = "Save Tomorrow's Intention";
            saveBtn.disabled = false;
          }, 1800);
        }
      } catch (err) {
        if (saveBtn) {
          saveBtn.textContent = "Save Failed";
          setTimeout(() => {
            saveBtn.textContent = "Save Tomorrow's Intention";
            saveBtn.disabled = false;
          }, 2000);
        }
      }
    });
  }

  // Reload when navigating to #view-requirements or #view-student-portal
  window.addEventListener("hashchange", () => {
    if (window.location.hash === "#view-requirements") {
      loadRequirements();
    }
  });
}

// ===================================================================
// MODULE 6: BUS 1, BUS 2, BUS 3 PASSENGER MANIFESTS & SAFE BOARDING
// ===================================================================

async function loadManifest(busId) {
  try {
    const data = await apiRequest(`/manifest?bus_id=${busId}`);
    if (!data || !Array.isArray(data.students)) return;

    // 1. Update Metric Cards
    const allocElem = document.querySelector(`#driver-${busId}-allocated`);
    const vacantElem = document.querySelector(`#driver-${busId}-vacant`);
    const boardedElem = document.querySelector(`#driver-${busId}-boarded`);
    const titleElem = document.querySelector(`#driver-${busId}-manifest-title`);

    if (allocElem) {
      allocElem.textContent = `${data.totalAllocated} / ${data.capacity}`;
    }
    if (vacantElem) {
      vacantElem.textContent = `${data.seatsVacant} Seats Vacant`;
    }
    if (boardedElem) {
      boardedElem.textContent = `${data.totalBoarded} / ${data.totalAllocated}`;
    }
    if (titleElem) {
      titleElem.textContent = `${data.busName} Passenger Manifest (${data.totalAllocated} Allocated Students)`;
    }

    // 2. Render Table Body
    const tbody = document.querySelector(`#driver-${busId}-manifest-body`);
    if (!tbody) return;

    tbody.replaceChildren();

    if (data.students.length === 0) {
      const row = document.createElement("tr");
      row.innerHTML = `<td colspan="7" style="text-align:center;color:var(--slate-500);padding:24px;">No students allocated to ${escapeHtml(data.busName)} in database yet.</td>`;
      tbody.appendChild(row);
      return;
    }

    data.students.forEach((stu, index) => {
      const row = document.createElement("tr");

      // Col 1: Seat #
      const seatTd = document.createElement("td");
      seatTd.textContent = stu.seatNo || String(index + 1).padStart(2, "0");
      row.appendChild(seatTd);

      // Col 2: Student ID
      const idTd = document.createElement("td");
      idTd.innerHTML = `<strong>#${stu.id}</strong>`;
      row.appendChild(idTd);

      // Col 3: Name
      const nameTd = document.createElement("td");
      nameTd.textContent = stu.name;
      row.appendChild(nameTd);

      // Col 4: Pickup Stop
      const stopTd = document.createElement("td");
      stopTd.textContent = stu.stop || "Not Specified";
      row.appendChild(stopTd);

      // Col 5: Pass Auth Status
      const authTd = document.createElement("td");
      const authBadge = document.createElement("span");
      authBadge.className = stu.authorized ? "badge badge-success" : "badge badge-danger";
      authBadge.textContent = stu.authorized ? "Authorized" : "Unauthorized";
      authTd.appendChild(authBadge);
      row.appendChild(authTd);

      // Col 6: Declared Tomorrow
      const tomorrowTd = document.createElement("td");
      tomorrowTd.textContent = stu.declaredTomorrow ? "Yes" : "No";
      if (!stu.declaredTomorrow) {
        tomorrowTd.style.color = "var(--slate-500)";
      }
      row.appendChild(tomorrowTd);

      // Col 7: Safe Boarding Checkbox (Enforcing Requirement 8)
      const boardTd = document.createElement("td");
      if (!stu.authorized) {
        // Bar unauthorized students from boarding even if they declared demand
        const unauthBadge = document.createElement("span");
        unauthBadge.className = "badge badge-danger font-semibold";
        unauthBadge.textContent = "Pass Suspended";
        unauthBadge.title = "Student is unauthorized. Transit denied until transport fee cleared.";
        boardTd.appendChild(unauthBadge);
      } else {
        const label = document.createElement("label");
        label.style.display = "inline-flex";
        label.style.alignItems = "center";
        label.style.gap = "6px";
        label.style.cursor = "pointer";

        const chk = document.createElement("input");
        chk.type = "checkbox";
        chk.checked = !!stu.boarded;

        const statusLabel = document.createElement("span");
        if (chk.checked) {
          statusLabel.className = "badge badge-success";
          statusLabel.textContent = "✓ Boarded";
        } else {
          statusLabel.textContent = "Boarded";
        }

        chk.addEventListener("change", async () => {
          chk.disabled = true;
          try {
            const todayStr = new Date().toISOString().split("T")[0];
            await apiRequest("/attendance", {
              method: "POST",
              body: new URLSearchParams({
                id: stu.id,
                date: todayStr,
                status: "Present",
                boarded: chk.checked ? "true" : "false"
              })
            });
            showBackendStatus(true, `Boarding status updated for #${stu.id} (${stu.name})`);
            await loadManifest(busId);
            loadBuses();
            loadAttendance(todayStr);
          } catch (err) {
            chk.checked = !chk.checked;
            showBackendStatus(false, "Failed to update boarding status: " + err.message);
          } finally {
            chk.disabled = false;
          }
        });

        label.appendChild(chk);
        label.appendChild(statusLabel);
        boardTd.appendChild(label);
      }
      row.appendChild(boardTd);

      tbody.appendChild(row);
    });

    console.log(`Bus ${busId} manifest loaded from database: ${data.students.length} students`);
  } catch (err) {
    console.error(`Failed to load manifest for Bus ${busId}:`, err);
    showBackendStatus(false, `Failed to load Bus ${busId} manifest from database`);
  }
}

function initDriverManifests() {
  loadManifest(1);
  loadManifest(2);
  loadManifest(3);

  window.addEventListener("hashchange", () => {
    const hash = window.location.hash;
    if (hash === "#view-driver-1") loadManifest(1);
    else if (hash === "#view-driver-2") loadManifest(2);
    else if (hash === "#view-driver-3") loadManifest(3);
  });
}

async function loadBreakdowns() {
  try {
    const data = await apiRequest("/breakdowns", { headers: {} });

    // 1. Update Standby Depot Badge in #view-breakdowns
    const standbyDepotBadge = document.querySelector("#standby-depot-badge");
    if (standbyDepotBadge) {
      if (data.standbyAvailable) {
        standbyDepotBadge.textContent = "Standby Ready";
        standbyDepotBadge.className = "badge badge-success";
      } else {
        standbyDepotBadge.textContent = "Deployed • En Route";
        standbyDepotBadge.className = "badge badge-danger";
      }
    }

    // 2. Update Wizard Standby Availability indicator & Deploy button
    const wizardStandbyBadge = document.querySelector("#wizard-standby-availability");
    const wizardDeployBtn = document.querySelector("#btn-deploy-minibus");
    if (wizardStandbyBadge) {
      if (data.standbyAvailable) {
        wizardStandbyBadge.textContent = "Standby Ready";
        wizardStandbyBadge.className = "badge badge-success";
      } else {
        wizardStandbyBadge.textContent = "Unavailable (Deployed)";
        wizardStandbyBadge.className = "badge badge-danger";
      }
    }
    if (wizardDeployBtn) {
      wizardDeployBtn.disabled = !data.standbyAvailable;
    }

    // 3. Update Admin Dashboard Standby Shuttle Banner
    const adminBanner = document.querySelector("#admin-standby-banner");
    const adminTitle = document.querySelector("#admin-standby-title");
    const adminText = document.querySelector("#admin-standby-text");
    const adminBtn = document.querySelector("#admin-standby-btn");
    if (adminBanner && adminTitle && adminText && adminBtn) {
      if (data.activeBreakdowns && data.activeBreakdowns.length > 0) {
        const bd = data.activeBreakdowns[0];
        adminBanner.className = "alert-banner alert-warning mb-6";
        adminTitle.textContent = `EMERGENCY RELIEF ACTIVE: ${bd.brokenBusName} Incident`;
        adminText.innerHTML = `Mini Bus <code>TN-09-MB-201</code> is deployed to <strong>${escapeHtml(bd.brokenRoute || "Affected Corridor")}</strong> with a <strong>${bd.delayMinutes} min</strong> delay broadcast. Regular bus undergoing maintenance.`;
        adminBtn.textContent = "View Live Diagnostics";
        adminBtn.href = "#view-breakdowns";
      } else {
        adminBanner.className = "alert-banner alert-info mb-6";
        adminTitle.textContent = "Emergency Standby Mini Bus Active";
        adminText.innerHTML = "Vehicle <code>TN-09-MB-201</code> (10 seats) is on standby at central depot. In the event of a regular bus breakdown, Admin can trigger the Recovery Wizard to reassign stranded passengers.";
        adminBtn.textContent = "Recovery Wizard";
        adminBtn.href = "#modal-breakdown-wizard";
      }
    }

    // 4. Update Student Transit Hub Live Delay Notice
    const studentNotice = document.querySelector("#student-portal-delay-notice");
    const studentTitle = document.querySelector("#student-portal-delay-title");
    const studentText = document.querySelector("#student-portal-delay-text");
    if (studentNotice && studentTitle && studentText) {
      if (data.activeBreakdowns && data.activeBreakdowns.length > 0) {
        studentNotice.style.display = "flex";
        studentTitle.textContent = "Live Transit Advisory: Vehicle Breakdown & Relief Shuttle Active";
        const alerts = data.activeBreakdowns.map(bd =>
          `⚠️ <strong>${escapeHtml(bd.brokenBusName)} (${escapeHtml(bd.brokenRoute || "Regular Route")})</strong> is currently experiencing a breakdown. Standby relief Mini Bus (TN-09-MB-201) has been dispatched for pickup. Estimated arrival delay: <strong class="text-danger">+${bd.delayMinutes} Minutes</strong>.`
        ).join("<br>");
        studentText.innerHTML = alerts;
      } else {
        studentNotice.style.display = "none";
      }
    }

    // 5. Populate Live Fleet Diagnostics Table (#fleet-diagnostics-table-body)
    const tbody = document.querySelector("#fleet-diagnostics-table-body");
    if (tbody && data.fleet) {
      tbody.replaceChildren();

      data.fleet.forEach(bus => {
        const row = document.createElement("tr");

        // Col 1: Bus Name
        const nameTd = document.createElement("td");
        const strong = document.createElement("strong");
        strong.textContent = bus.name;
        nameTd.appendChild(strong);
        row.appendChild(nameTd);

        // Col 2: Registration Number
        const regTd = document.createElement("td");
        regTd.textContent = bus.number || "—";
        row.appendChild(regTd);

        // Col 3: Driver Name
        const driverTd = document.createElement("td");
        driverTd.textContent = bus.driver || "Unassigned";
        row.appendChild(driverTd);

        // Col 4: Route
        const routeTd = document.createElement("td");
        routeTd.textContent = bus.route || "—";
        row.appendChild(routeTd);

        // Col 5: Operating Status
        const statusTd = document.createElement("td");
        const badge = document.createElement("span");
        if (bus.status === "Active") {
          badge.className = "badge badge-success";
          badge.textContent = "Active • Normal";
        } else if (bus.status === "Breakdown Reported") {
          badge.className = "badge badge-danger";
          badge.textContent = "Breakdown Reported";
        } else if (bus.status === "Deployed (Relief)") {
          badge.className = "badge badge-warning";
          badge.textContent = "Deployed (Relief)";
        } else if (bus.status === "Standby Depot") {
          badge.className = "badge badge-neutral";
          badge.textContent = "Standby Depot";
        } else {
          badge.className = "badge badge-info";
          badge.textContent = bus.status;
        }
        statusTd.appendChild(badge);
        row.appendChild(statusTd);

        // Col 6: Active Delay
        const delayTd = document.createElement("td");
        if (bus.id === 4) {
          if (!data.standbyAvailable) {
            delayTd.innerHTML = '<span class="text-amber font-semibold">Relief Active</span>';
          } else {
            delayTd.innerHTML = "&mdash;";
          }
        } else {
          if (bus.delayMinutes > 0) {
            delayTd.innerHTML = `<strong class="text-danger">+${bus.delayMinutes} Mins</strong>`;
          } else {
            delayTd.textContent = "0 Mins";
          }
        }
        row.appendChild(delayTd);

        // Col 7: Emergency Action
        const actionTd = document.createElement("td");
        if (bus.id === 4) {
          if (data.standbyAvailable) {
            actionTd.innerHTML = `<a href="#modal-breakdown-wizard" class="btn btn-primary btn-sm">Deploy Now</a>`;
          } else {
            actionTd.innerHTML = `<span class="badge badge-warning">On Relief Run</span>`;
          }
        } else {
          if (bus.status === "Breakdown Reported") {
            const resolveBtn = document.createElement("button");
            resolveBtn.type = "button";
            resolveBtn.className = "btn btn-success btn-sm btn-resolve-breakdown";
            resolveBtn.textContent = "Resolve & Restore";
            resolveBtn.setAttribute("data-bus-id", bus.id);
            resolveBtn.addEventListener("click", () => resolveBreakdown(bus.id));
            actionTd.appendChild(resolveBtn);
          } else {
            const reportBtn = document.createElement("a");
            reportBtn.href = "#modal-breakdown-wizard";
            reportBtn.className = "btn btn-outline btn-sm btn-report-issue";
            reportBtn.textContent = "Report Issue";
            reportBtn.setAttribute("data-bus-id", bus.id);
            reportBtn.addEventListener("click", () => {
              const sel = document.querySelector("#wizard-select-bus");
              if (sel) sel.value = String(bus.id);
            });
            actionTd.appendChild(reportBtn);
          }
        }
        row.appendChild(actionTd);

        tbody.appendChild(row);
      });
    }

    console.log("Fleet diagnostics and breakdowns loaded from database");
  } catch (err) {
    console.error("Failed to load breakdowns:", err);
    showBackendStatus(false, "Failed to load breakdown status from database: " + err.message);
  }
}

async function resolveBreakdown(busId) {
  if (!confirm(`Are you sure you want to mark Bus #${busId} breakdown as resolved and return Standby Mini Bus to the depot?`)) {
    return;
  }
  try {
    const body = new URLSearchParams({
      bus_id: String(busId),
      action: "resolve"
    });
    const res = await apiRequest("/breakdowns?action=resolve", { method: "POST", body });
    showBackendStatus(true, res.message || "Breakdown resolved successfully");
    await loadBreakdowns();
    loadBuses();
  } catch (err) {
    showBackendStatus(false, "Failed to resolve breakdown: " + err.message);
  }
}

function initBreakdownWizard() {
  const form = document.querySelector("#form-breakdown-wizard");
  if (!form) return;

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const errBox = document.querySelector("#wizard-error-msg");
    const errText = document.querySelector("#wizard-error-text");
    if (errBox) errBox.style.display = "none";

    const busSelect = document.querySelector("#wizard-select-bus");
    const delayInput = document.querySelector("#wizard-delay-minutes");
    const submitBtn = document.querySelector("#btn-deploy-minibus");

    const busId = busSelect ? busSelect.value : "";
    const delay = delayInput ? parseInt(delayInput.value, 10) : 20;

    if (!busId) {
      if (errBox && errText) {
        errText.textContent = "Please select the vehicle experiencing an issue.";
        errBox.style.display = "block";
      }
      return;
    }

    if (isNaN(delay) || delay < 5 || delay > 120) {
      if (errBox && errText) {
        errText.textContent = "Delay duration must be between 5 and 120 minutes.";
        errBox.style.display = "block";
      }
      return;
    }

    if (submitBtn) submitBtn.disabled = true;

    try {
      const body = new URLSearchParams({
        bus_id: busId,
        replacement_id: "4",
        delay: String(delay)
      });
      const res = await apiRequest("/breakdowns", { method: "POST", body });
      showBackendStatus(true, res.message || "Standby Mini Bus successfully deployed");
      window.location.hash = "view-breakdowns";
      await loadBreakdowns();
      loadBuses();
    } catch (err) {
      if (errBox && errText) {
        errText.textContent = err.message;
        errBox.style.display = "block";
      }
      showBackendStatus(false, err.message);
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  });

  window.addEventListener("hashchange", () => {
    const hash = window.location.hash;
    if (hash === "#view-breakdowns" || hash === "#modal-breakdown-wizard" || hash === "#view-admin-dashboard" || hash === "#view-student-portal" || hash === "#view-buses") {
      loadBreakdowns();
    }
  });
}

document.addEventListener("DOMContentLoaded", () => {
  checkBackend();
  saveStudent();
  loadStudents();
  initAttendanceModule();
  initBusCapacityForm();
  loadBuses();
  loadRoutes();
  loadFees();
  initRequirementsModule();
  loadRequirements();
  initDriverManifests();
  initBreakdownWizard();
  loadBreakdowns();
});

