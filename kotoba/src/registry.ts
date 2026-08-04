/**
 * open-rail kotoba — station + line + train-run registries + coverage.
 * AT PDS records (no RW). A line references an ordered sequence of existing
 * stations (≥2); a train run references an existing line.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  LINE_COLLECTION,
  RUN_COLLECTION,
  RUN_STATUSES,
  STATION_COLLECTION,
  lineDid,
  lineRkey,
  runDid,
  runRkey,
  stationDid,
  stationRkey,
  type CoverageInput,
  type CoverageOutput,
  type DefineLineInput,
  type DefineLineOutput,
  type DefineStationInput,
  type DefineStationOutput,
  type GetLineInput,
  type GetLineOutput,
  type GetRunInput,
  type GetRunOutput,
  type GetStationInput,
  type GetStationOutput,
  type LineRecord,
  type LineView,
  type ListLinesInput,
  type ListLinesOutput,
  type ListStationsInput,
  type ListStationsOutput,
  type ListTrainRunsInput,
  type ListTrainRunsOutput,
  type RecordRunStatusInput,
  type RecordRunStatusOutput,
  type RunStatus,
  type ScheduleTrainInput,
  type ScheduleTrainOutput,
  type StationRecord,
  type StationView,
  type TrainRunRecord,
  type TrainRunView,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

async function exists(e: Etzhayyim, collection: string, rkey: string): Promise<boolean> {
  const resp = await e.read({ collection, rkey }).catch(() => ({ records: [] }));
  return Boolean(resp.records[0]?.value);
}

// ─── Station ────────────────────────────────────────────────────────

export async function defineStation(e: Etzhayyim, input: DefineStationInput): Promise<DefineStationOutput> {
  if (!input.stationId || !input.name) return { status: "rejected", error: "missingRequiredFields" };
  const rkey = stationRkey(input.stationId);
  const existing = await e.read<StationRecord>({ collection: STATION_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", stationUri: existing.records[0].uri, did: existing.records[0].value.did, stationId: input.stationId };
  }
  const did = stationDid(input.stationId);
  const record: StationRecord = { did, stationId: input.stationId, name: input.name, location: input.location, createdAt: new Date().toISOString() };
  const receipt = await e.write({ collection: STATION_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "defined", stationUri: receipt.uri, did, stationId: input.stationId };
}

export async function getStation(e: Etzhayyim, input: GetStationInput): Promise<GetStationOutput> {
  if (!input.stationId) return { error: "invalidStationId" };
  const resp = await e.read<StationRecord>({ collection: STATION_COLLECTION, rkey: stationRkey(input.stationId) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { station: { ...r.value, stationUri: r.uri } };
}

export async function listStations(e: Etzhayyim, input: ListStationsInput = {}): Promise<ListStationsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<StationRecord>({ collection: STATION_COLLECTION, cursor: input.cursor, limit });
  const items: StationView[] = resp.records.map((r) => ({ ...r.value, stationUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Line ───────────────────────────────────────────────────────────

export async function defineLine(e: Etzhayyim, input: DefineLineInput): Promise<DefineLineOutput> {
  if (!input.lineId || !input.name) return { status: "rejected", error: "missingRequiredFields" };
  if (!input.stations || input.stations.length < 2) {
    return { status: "rejected", error: "lineNeedsAtLeastTwoStations" };
  }
  for (const s of input.stations) {
    if (!s.stationId) return { status: "rejected", error: "invalidStationInSequence" };
    if (!(await exists(e, STATION_COLLECTION, stationRkey(s.stationId)))) {
      return { status: "stationNotFound", error: `stationNotFound:${s.stationId}` };
    }
  }
  const rkey = lineRkey(input.lineId);
  const existing = await e.read<LineRecord>({ collection: LINE_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", lineUri: existing.records[0].uri, did: existing.records[0].value.did, lineId: input.lineId };
  }
  const did = lineDid(input.lineId);
  const record: LineRecord = {
    did,
    lineId: input.lineId,
    name: input.name,
    operator: input.operator,
    stations: input.stations.map((s) => ({ stationId: s.stationId.toLowerCase(), kmPostM: s.kmPostM, dwellSec: s.dwellSec })),
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: LINE_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "defined", lineUri: receipt.uri, did, lineId: input.lineId };
}

export async function getLine(e: Etzhayyim, input: GetLineInput): Promise<GetLineOutput> {
  if (!input.lineId) return { error: "invalidLineId" };
  const resp = await e.read<LineRecord>({ collection: LINE_COLLECTION, rkey: lineRkey(input.lineId) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { line: { ...r.value, lineUri: r.uri } };
}

export async function listLines(e: Etzhayyim, input: ListLinesInput = {}): Promise<ListLinesOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<LineRecord>({ collection: LINE_COLLECTION, cursor: input.cursor, limit });
  const items: LineView[] = resp.records
    .filter((r) => (input.operator ? r.value.operator === input.operator : true))
    .map((r) => ({ ...r.value, lineUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Train run ──────────────────────────────────────────────────────

export async function scheduleTrain(e: Etzhayyim, input: ScheduleTrainInput): Promise<ScheduleTrainOutput> {
  if (!input.runId || !input.lineId) return { status: "rejected", error: "missingRequiredFields" };
  if (!(await exists(e, LINE_COLLECTION, lineRkey(input.lineId)))) {
    return { status: "lineNotFound", error: "lineNotFound" };
  }
  const rkey = runRkey(input.runId);
  const existing = await e.read<TrainRunRecord>({ collection: RUN_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", runUri: existing.records[0].uri, did: existing.records[0].value.did, runId: input.runId };
  }
  const did = runDid(input.runId);
  const record: TrainRunRecord = {
    did,
    runId: input.runId,
    lineId: input.lineId.toLowerCase(),
    originStationId: input.originStationId ? input.originStationId.toLowerCase() : undefined,
    destStationId: input.destStationId ? input.destStationId.toLowerCase() : undefined,
    serviceDay: input.serviceDay,
    status: "scheduled",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: RUN_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "scheduled", runUri: receipt.uri, did, runId: input.runId };
}

export async function recordRunStatus(e: Etzhayyim, input: RecordRunStatusInput): Promise<RecordRunStatusOutput> {
  if (!input.runId || !RUN_STATUSES.has(input.status)) return { status: "rejected", error: "invalidStatus" };
  const rkey = runRkey(input.runId);
  const resp = await e.read<TrainRunRecord>({ collection: RUN_COLLECTION, rkey }).catch(() => ({ records: [] }));
  const run = resp.records[0]?.value;
  if (!run) return { status: "notFound", error: "runNotFound" };
  if (run.status === "completed" || run.status === "cancelled") {
    return { status: "rejected", error: `runTerminal:${run.status}` };
  }
  await e.write({ collection: RUN_COLLECTION, record: { ...run, status: input.status } as unknown as Record<string, unknown>, rkey });
  return { status: "updated", runId: input.runId, newStatus: input.status };
}

export async function getRun(e: Etzhayyim, input: GetRunInput): Promise<GetRunOutput> {
  if (!input.runId) return { error: "invalidRunId" };
  const resp = await e.read<TrainRunRecord>({ collection: RUN_COLLECTION, rkey: runRkey(input.runId) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { run: { ...r.value, runUri: r.uri } };
}

export async function listTrainRuns(e: Etzhayyim, input: ListTrainRunsInput = {}): Promise<ListTrainRunsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<TrainRunRecord>({ collection: RUN_COLLECTION, cursor: input.cursor, limit });
  const items: TrainRunView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.lineId && v.lineId !== input.lineId.toLowerCase()) return false;
      if (input.serviceDay && v.serviceDay !== input.serviceDay) return false;
      if (input.status && v.status !== input.status) return false;
      return true;
    })
    .map((r) => ({ ...r.value, runUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Coverage ───────────────────────────────────────────────────────

async function countAll<T>(e: Etzhayyim, collection: string, maxScan: number, onRow: (v: T) => void): Promise<number> {
  let cursor: string | undefined;
  let scanned = 0;
  while (scanned < maxScan) {
    const page = await e.read<T>({ collection, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      onRow(r.value);
      scanned += 1;
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return scanned;
}

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const stationCount = await countAll<StationRecord>(e, STATION_COLLECTION, maxScan, () => {});
  const lineCount = await countAll<LineRecord>(e, LINE_COLLECTION, maxScan, () => {});
  const runsByStatus: Record<string, number> = {};
  const runCount = await countAll<TrainRunRecord>(e, RUN_COLLECTION, maxScan, (v) => {
    runsByStatus[v.status as RunStatus] = (runsByStatus[v.status as RunStatus] ?? 0) + 1;
  });
  return {
    stationCount,
    lineCount,
    runCount,
    runsByStatus,
    truncated: stationCount >= maxScan || lineCount >= maxScan || runCount >= maxScan,
  };
}
