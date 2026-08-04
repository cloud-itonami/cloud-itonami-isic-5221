// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 etzhayyim Japan株式会社 / etzhayyim. All rights reserved.
// Licensed under the Apache License, Version 2.0 — see LICENSE at repo root.

// etzhayyim-project-open-rail — railway operations + network design (CF Worker + D1)
//
// 7 XRPC methods under com.etzhayyim.apps.openRail.*:
//   defineLine       (procedure)  network design — line + station sequence
//   getLine          (query)      line + stations
//   listLines        (query)      paginated lines
//   scheduleTrain    (procedure)  publish a single train run
//   listTrainRuns    (query)      runs by line / day / status
//   reportIncident   (procedure)  safety / delay incident
//   listIncidents    (query)      incidents by line / since
//
// Storage: D1. Stations are an ordered list per line (kmPost monotonic).
// Incident severity is decided by the openRail.incidentSeverity DMN
// (mirrored in code). severity ≥ "major" emits app.bsky.feed.post via PDS.

import AV1 from "../../dodaf/AV-1.json";
import OV1 from "../../dodaf/OV-1.json";
import OV5b from "../../dodaf/OV-5b.json";
import OV6a from "../../dodaf/OV-6a.json";
import CV2 from "../../dodaf/CV-2.json";
import SV1 from "../../dodaf/SV-1.json";
import defineLineForm from "../../forms/defineLine.form.json";
import reportIncidentForm from "../../forms/reportIncident.form.json";
import { bootstrapDodaf } from "./dodaf-bootstrap";

const DODAF_VIEWS: Record<string, any> = {
  "open-rail.AV-1": AV1, "open-rail.OV-1": OV1, "open-rail.OV-5b": OV5b,
  "open-rail.OV-6a": OV6a, "open-rail.CV-2": CV2, "open-rail.SV-1": SV1,
};
const FORMS: Record<string, any> = {
  "openRail.defineLine.v1": defineLineForm,
  "openRail.reportIncident.v1": reportIncidentForm,
};

export interface Env {
  RAIL_DB: D1Database;
  PDS?: Fetcher;
  AUTH_SERVICE?: Fetcher;
  APP_HANDLE: string;
  PRIMARY_DID: string;
}

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS lines (
    line_did TEXT PRIMARY KEY,
    line_code TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    operator_did TEXT NOT NULL,
    gauge_mm INTEGER NOT NULL,
    defined_at TEXT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS stations (
    station_did TEXT PRIMARY KEY,
    line_did TEXT NOT NULL,
    seq INTEGER NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    km_post REAL NOT NULL,
    dwell_sec INTEGER NOT NULL DEFAULT 30,
    UNIQUE(line_did, seq),
    FOREIGN KEY (line_did) REFERENCES lines(line_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_stations_line ON stations(line_did, seq)`,
  `CREATE TABLE IF NOT EXISTS train_runs (
    run_did TEXT PRIMARY KEY,
    line_did TEXT NOT NULL,
    run_number TEXT NOT NULL,
    service_date TEXT NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('up','down')),
    origin_station_did TEXT NOT NULL,
    dest_station_did TEXT NOT NULL,
    stops_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'scheduled',
    scheduled_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_runs_line_date ON train_runs(line_did, service_date)`,
  `CREATE TABLE IF NOT EXISTS incidents (
    incident_did TEXT PRIMARY KEY,
    line_did TEXT NOT NULL,
    station_did TEXT,
    occurred_at TEXT NOT NULL,
    category TEXT NOT NULL,
    delay_min INTEGER NOT NULL,
    injuries INTEGER NOT NULL,
    derailment INTEGER NOT NULL,
    severity TEXT NOT NULL,
    require_gov_report INTEGER NOT NULL,
    description TEXT,
    reported_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_incidents_line_time ON incidents(line_did, occurred_at DESC)`,
];

let schemaReady = false;
async function ensureSchema(db: D1Database) {
  if (schemaReady) return;
  for (const stmt of SCHEMA) await db.exec(stmt.replace(/\s+/g, " "));
  schemaReady = true;
}

function nanoid(len = 12): string {
  const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  let out = "";
  for (let i = 0; i < len; i++) out += alphabet[bytes[i] % alphabet.length];
  return out;
}
const now = () => new Date().toISOString();

type XrpcError =
  | "InvalidRequest" | "LineNotFound" | "StationNotFound" | "Conflict"
  | "Unauthorized" | "InternalError";

function json(b: unknown, status = 200): Response {
  return new Response(JSON.stringify(b), { status, headers: { "content-type": "application/json" } });
}
function err(error: XrpcError, message: string, status = 400): Response {
  return json({ error, message }, status);
}

// DMN-equivalent severity classifier (mirrors dmn/incident-severity.dmn).
function classifySeverity(input: { injuries: number; delayMin: number; derailment: boolean })
  : { severity: "minor" | "moderate" | "major" | "critical"; requireGovReport: boolean } {
  if (input.derailment) return { severity: "critical", requireGovReport: true };
  if (input.injuries >= 1) return { severity: "major", requireGovReport: true };
  if (input.delayMin >= 60) return { severity: "major", requireGovReport: true };
  if (input.delayMin >= 15) return { severity: "moderate", requireGovReport: false };
  return { severity: "minor", requireGovReport: false };
}

async function defineLine(env: Env, input: any): Promise<Response> {
  const { lineCode, displayName, operatorDid, gauge, stations } = input ?? {};
  if (typeof lineCode !== "string" || !/^[A-Z0-9-]{2,16}$/.test(lineCode))
    return err("InvalidRequest", "lineCode required (A-Z0-9-, 2-16)");
  if (typeof displayName !== "string" || !displayName.length)
    return err("InvalidRequest", "displayName required");
  if (typeof operatorDid !== "string" || !operatorDid.startsWith("did:"))
    return err("InvalidRequest", "operatorDid must be a DID");
  if (!Number.isInteger(gauge) || gauge < 600 || gauge > 2000)
    return err("InvalidRequest", "gauge must be 600..2000 mm");
  if (!Array.isArray(stations) || stations.length < 2)
    return err("InvalidRequest", "stations[] requires ≥ 2 entries");
  // Monotonic km
  let prev = -Infinity;
  for (const s of stations) {
    if (typeof s?.code !== "string" || typeof s?.name !== "string" || typeof s?.kmPost !== "number")
      return err("InvalidRequest", "each station needs {code,name,kmPost}");
    if (!(s.kmPost > prev)) return err("InvalidRequest", `kmPost must be strictly increasing at ${s.code}`);
    prev = s.kmPost;
  }

  const lineId = nanoid(10);
  const lineDid = `did:web:${env.APP_HANDLE}:line:${lineId}`;
  const definedAt = now();

  const stmts: D1PreparedStatement[] = [
    env.RAIL_DB.prepare(
      `INSERT INTO lines (line_did, line_code, display_name, operator_did, gauge_mm, defined_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(lineDid, lineCode, displayName, operatorDid, gauge, definedAt),
  ];
  const stationDids: string[] = [];
  for (let i = 0; i < stations.length; i++) {
    const s = stations[i];
    const sid = nanoid(10);
    const stationDid = `did:web:${env.APP_HANDLE}:station:${sid}`;
    stationDids.push(stationDid);
    stmts.push(
      env.RAIL_DB.prepare(
        `INSERT INTO stations (station_did, line_did, seq, code, name, km_post, dwell_sec)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      ).bind(stationDid, lineDid, i, s.code, s.name, s.kmPost, Number.isInteger(s.dwellSec) ? s.dwellSec : 30)
    );
  }
  await env.RAIL_DB.batch(stmts);

  return json({ lineDid, lineCode, displayName, operatorDid, gauge, stationDids, definedAt });
}

async function getLine(env: Env, params: URLSearchParams): Promise<Response> {
  const lineDid = params.get("lineDid");
  if (!lineDid) return err("InvalidRequest", "lineDid required");
  const line = await env.RAIL_DB.prepare(`SELECT * FROM lines WHERE line_did = ?`).bind(lineDid).first<any>();
  if (!line) return err("LineNotFound", "no such line", 404);
  const sts = await env.RAIL_DB.prepare(
    `SELECT * FROM stations WHERE line_did = ? ORDER BY seq ASC`
  ).bind(lineDid).all<any>();
  return json({
    lineDid: line.line_did,
    lineCode: line.line_code,
    displayName: line.display_name,
    operatorDid: line.operator_did,
    gauge: line.gauge_mm,
    definedAt: line.defined_at,
    stations: (sts.results ?? []).map((s) => ({
      stationDid: s.station_did, seq: s.seq, code: s.code, name: s.name,
      kmPost: s.km_post, dwellSec: s.dwell_sec,
    })),
  });
}

async function listLines(env: Env, params: URLSearchParams): Promise<Response> {
  const limit = Math.min(200, Math.max(1, Number(params.get("limit") ?? 50)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const total = await env.RAIL_DB.prepare(`SELECT COUNT(*) AS c FROM lines`).first<{ c: number }>();
  const rows = await env.RAIL_DB.prepare(
    `SELECT * FROM lines ORDER BY defined_at DESC LIMIT ? OFFSET ?`
  ).bind(limit, offset).all<any>();
  return json({
    lines: (rows.results ?? []).map((r) => ({
      lineDid: r.line_did, lineCode: r.line_code, displayName: r.display_name,
      operatorDid: r.operator_did, gauge: r.gauge_mm, definedAt: r.defined_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function scheduleTrain(env: Env, input: any): Promise<Response> {
  const { lineDid, runNumber, serviceDate, direction, originStationDid, destStationDid, stops } = input ?? {};
  if (typeof lineDid !== "string") return err("InvalidRequest", "lineDid required");
  if (typeof runNumber !== "string") return err("InvalidRequest", "runNumber required");
  if (typeof serviceDate !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(serviceDate))
    return err("InvalidRequest", "serviceDate must be YYYY-MM-DD");
  if (direction !== "up" && direction !== "down")
    return err("InvalidRequest", "direction must be up|down");
  if (typeof originStationDid !== "string" || typeof destStationDid !== "string")
    return err("InvalidRequest", "origin/dest station DIDs required");
  if (originStationDid === destStationDid)
    return err("InvalidRequest", "origin must differ from destination");
  if (!Array.isArray(stops) || stops.length < 2)
    return err("InvalidRequest", "stops[] requires ≥ 2 entries");

  // Validate stops are subset of line, in declared direction order
  const sts = await env.RAIL_DB.prepare(
    `SELECT station_did, seq FROM stations WHERE line_did = ? ORDER BY seq ASC`
  ).bind(lineDid).all<any>();
  const seqByDid = new Map<string, number>((sts.results ?? []).map((s: any) => [s.station_did, s.seq]));
  let last = direction === "up" ? -Infinity : Infinity;
  for (const stop of stops) {
    const did = stop?.stationDid;
    if (typeof did !== "string" || !seqByDid.has(did))
      return err("StationNotFound", `stop ${did} not on line`);
    const seq = seqByDid.get(did)!;
    if (direction === "up" ? !(seq > last) : !(seq < last))
      return err("InvalidRequest", "stops not in direction order");
    last = seq;
  }

  const runId = nanoid(12);
  const runDid = `did:web:${env.APP_HANDLE}:run:${runId}`;
  const scheduledAt = now();
  await env.RAIL_DB.prepare(
    `INSERT INTO train_runs (run_did, line_did, run_number, service_date, direction, origin_station_did, dest_station_did, stops_json, status, scheduled_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'scheduled', ?)`
  ).bind(runDid, lineDid, runNumber, serviceDate, direction, originStationDid, destStationDid,
         JSON.stringify(stops), scheduledAt).run();
  return json({ runDid, lineDid, runNumber, serviceDate, direction, status: "scheduled", scheduledAt });
}

async function listTrainRuns(env: Env, params: URLSearchParams): Promise<Response> {
  const lineDid = params.get("lineDid");
  if (!lineDid) return err("InvalidRequest", "lineDid required");
  const date = params.get("serviceDate");
  const status = params.get("status");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses = [`line_did = ?`]; const binds: any[] = [lineDid];
  if (date)   { clauses.push(`service_date = ?`); binds.push(date); }
  if (status) { clauses.push(`status = ?`);       binds.push(status); }
  const where = `WHERE ${clauses.join(" AND ")}`;
  const total = await env.RAIL_DB.prepare(`SELECT COUNT(*) AS c FROM train_runs ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.RAIL_DB.prepare(
    `SELECT * FROM train_runs ${where} ORDER BY service_date DESC, run_number ASC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    runs: (rows.results ?? []).map((r) => ({
      runDid: r.run_did, lineDid: r.line_did, runNumber: r.run_number,
      serviceDate: r.service_date, direction: r.direction,
      originStationDid: r.origin_station_did, destStationDid: r.dest_station_did,
      stops: JSON.parse(r.stops_json), status: r.status, scheduledAt: r.scheduled_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function reportIncident(env: Env, input: any): Promise<Response> {
  const { lineDid, stationDid, occurredAt, category, delayMin, injuries, derailment, description } = input ?? {};
  if (typeof lineDid !== "string") return err("InvalidRequest", "lineDid required");
  if (typeof occurredAt !== "string") return err("InvalidRequest", "occurredAt required");
  if (typeof category !== "string") return err("InvalidRequest", "category required");
  if (!Number.isFinite(delayMin) || delayMin < 0) return err("InvalidRequest", "delayMin ≥ 0");
  if (!Number.isFinite(injuries) || injuries < 0) return err("InvalidRequest", "injuries ≥ 0");

  const line = await env.RAIL_DB.prepare(`SELECT line_did FROM lines WHERE line_did = ?`)
    .bind(lineDid).first<any>();
  if (!line) return err("LineNotFound", "no such line", 404);

  const { severity, requireGovReport } = classifySeverity({
    injuries: Number(injuries), delayMin: Number(delayMin), derailment: !!derailment,
  });
  const id = nanoid(12);
  const incidentDid = `did:web:${env.APP_HANDLE}:incident:${id}`;
  const reportedAt = now();
  await env.RAIL_DB.prepare(
    `INSERT INTO incidents (incident_did, line_did, station_did, occurred_at, category, delay_min, injuries, derailment, severity, require_gov_report, description, reported_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(incidentDid, lineDid, stationDid ?? null, occurredAt, category, delayMin, injuries,
         derailment ? 1 : 0, severity, requireGovReport ? 1 : 0, description ?? null, reportedAt).run();

  // Audit post (best-effort)
  if (requireGovReport && env.PDS) {
    try {
      await env.PDS.fetch(`https://atproto.etzhayyim.com/xrpc/com.atproto.repo.createRecord`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          repo: env.PRIMARY_DID,
          collection: "app.bsky.feed.post",
          record: {
            $type: "app.bsky.feed.post",
            text: `[${severity.toUpperCase()}] rail incident on ${lineDid} (${category}, delay ${delayMin}min, injuries ${injuries})`,
            createdAt: reportedAt,
          },
        }),
      });
    } catch {}
  }
  return json({ incidentDid, lineDid, severity, requireGovReport, reportedAt });
}

async function listIncidents(env: Env, params: URLSearchParams): Promise<Response> {
  const lineDid = params.get("lineDid");
  if (!lineDid) return err("InvalidRequest", "lineDid required");
  const since = params.get("since");
  const minSeverity = params.get("minSeverity");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const sevRank = (s: string) => ({ minor: 0, moderate: 1, major: 2, critical: 3 } as any)[s] ?? -1;
  const clauses = [`line_did = ?`]; const binds: any[] = [lineDid];
  if (since) { clauses.push(`occurred_at >= ?`); binds.push(since); }
  const where = `WHERE ${clauses.join(" AND ")}`;
  const total = await env.RAIL_DB.prepare(`SELECT COUNT(*) AS c FROM incidents ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.RAIL_DB.prepare(
    `SELECT * FROM incidents ${where} ORDER BY occurred_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  let incidents = (rows.results ?? []).map((r) => ({
    incidentDid: r.incident_did, lineDid: r.line_did, stationDid: r.station_did ?? undefined,
    occurredAt: r.occurred_at, category: r.category, delayMin: r.delay_min, injuries: r.injuries,
    derailment: !!r.derailment, severity: r.severity, requireGovReport: !!r.require_gov_report,
    description: r.description ?? undefined, reportedAt: r.reported_at,
  }));
  if (minSeverity) incidents = incidents.filter((i) => sevRank(i.severity) >= sevRank(minSeverity));
  return json({ incidents, total: Number(total?.c ?? 0), offset, limit });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      await ensureSchema(env.RAIL_DB);
      const url = new URL(req.url);
      if (url.pathname === "/health" || url.pathname === "/_worker/health")
        return json({ ok: true, did: env.PRIMARY_DID, ts: now() });
      if (url.pathname === "/_app/meta") {
        if (env.PDS) { try { await bootstrapDodaf(env as any); } catch {} }
        return json({
          did: env.PRIMARY_DID, handle: env.APP_HANDLE,
          xrpc: [
            "com.etzhayyim.apps.openRail.defineLine",
            "com.etzhayyim.apps.openRail.getLine",
            "com.etzhayyim.apps.openRail.listLines",
            "com.etzhayyim.apps.openRail.scheduleTrain",
            "com.etzhayyim.apps.openRail.listTrainRuns",
            "com.etzhayyim.apps.openRail.reportIncident",
            "com.etzhayyim.apps.openRail.listIncidents",
          ],
          dodaf: Object.keys(DODAF_VIEWS),
          forms: Object.keys(FORMS),
          bpmn: ["defineLine", "reportIncident"],
          dmn:  ["openRail.incidentSeverity"],
        });
      }
      if (url.pathname === "/dodaf")
        return json({ views: Object.entries(DODAF_VIEWS).map(([id, v]: any) => ({
          viewId: id, viewType: v.viewType, title: v.title, version: v.version })) });
      if (url.pathname.startsWith("/dodaf/")) {
        const id = decodeURIComponent(url.pathname.slice("/dodaf/".length));
        const v = DODAF_VIEWS[id];
        return v ? json(v) : err("InvalidRequest", `no such view: ${id}`, 404);
      }
      if (url.pathname === "/forms")
        return json({ forms: Object.values(FORMS).map((f: any) => ({ formKey: f.formKey, name: f.name, version: f.version })) });
      if (url.pathname.startsWith("/forms/")) {
        const k = decodeURIComponent(url.pathname.slice("/forms/".length));
        const f = FORMS[k];
        return f ? json(f) : err("InvalidRequest", `no such form: ${k}`, 404);
      }
      if (!url.pathname.startsWith("/xrpc/"))
        return err("InvalidRequest", "only /xrpc/* is served", 404);
      const nsid = url.pathname.slice("/xrpc/".length);
      if (req.method === "GET") {
        switch (nsid) {
          case "com.etzhayyim.apps.openRail.getLine":       return await getLine(env, url.searchParams);
          case "com.etzhayyim.apps.openRail.listLines":     return await listLines(env, url.searchParams);
          case "com.etzhayyim.apps.openRail.listTrainRuns": return await listTrainRuns(env, url.searchParams);
          case "com.etzhayyim.apps.openRail.listIncidents": return await listIncidents(env, url.searchParams);
          default: return err("InvalidRequest", `unknown query NSID: ${nsid}`, 404);
        }
      }
      if (req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        switch (nsid) {
          case "com.etzhayyim.apps.openRail.defineLine":     return await defineLine(env, body);
          case "com.etzhayyim.apps.openRail.scheduleTrain":  return await scheduleTrain(env, body);
          case "com.etzhayyim.apps.openRail.reportIncident": return await reportIncident(env, body);
          default: return err("InvalidRequest", `unknown procedure NSID: ${nsid}`, 404);
        }
      }
      return err("InvalidRequest", "method not allowed", 405);
    } catch (e: any) {
      return err("InternalError", e?.message ?? String(e), 500);
    }
  },
};
