/**
 * open-rail kotoba — record types.
 *
 * Per ADR-2605203000 Option B. Rail operations: stations + lines (ordered
 * station sequence) + train runs. Registry on AT PDS records (replaces D1
 * lines/stations/train_runs). ADR-2605172000 kotoba.
 *
 * Identity hierarchy:
 *   did:web:open-rail.etzhayyim.com                       — controller
 *   did:web:open-rail.etzhayyim.com:station:{stationId}   — a station
 *   did:web:open-rail.etzhayyim.com:line:{lineId}         — a line
 *   did:web:open-rail.etzhayyim.com:run:{runId}           — a train run
 */

export const ORAIL_DID_PREFIX = "did:web:open-rail.etzhayyim.com:" as const;

export const STATION_COLLECTION = "com.etzhayyim.apps.openRail.station";
export const LINE_COLLECTION = "com.etzhayyim.apps.openRail.line";
export const RUN_COLLECTION = "com.etzhayyim.apps.openRail.run";

// ─── Station ────────────────────────────────────────────────────────

export interface StationRecord {
  did: string;
  stationId: string;
  name: string;
  location?: string;
  createdAt: string;
}

export interface StationView extends StationRecord {
  stationUri: string;
}

export interface DefineStationInput {
  stationId: string;
  name: string;
  location?: string;
}

export interface DefineStationOutput {
  status: "defined" | "alreadyExists" | "rejected";
  stationUri?: string;
  did?: string;
  stationId?: string;
  error?: string;
}

export interface GetStationInput {
  stationId: string;
}

export interface GetStationOutput {
  station?: StationView;
  error?: string;
}

export interface ListStationsInput {
  limit?: number;
  cursor?: string;
}

export interface ListStationsOutput {
  items: StationView[];
  cursor?: string;
  total: number;
}

// ─── Line ───────────────────────────────────────────────────────────

export interface LineStation {
  stationId: string;
  /** Distance from line origin, whole metres (AT Lexicon has no float). */
  kmPostM?: number;
  /** Standard dwell, seconds. */
  dwellSec?: number;
}

export interface LineRecord {
  did: string;
  lineId: string;
  name: string;
  operator?: string;
  /** Ordered station sequence (≥2). */
  stations: LineStation[];
  createdAt: string;
}

export interface LineView extends LineRecord {
  lineUri: string;
}

export interface DefineLineInput {
  lineId: string;
  name: string;
  stations: LineStation[];
  operator?: string;
}

export interface DefineLineOutput {
  status: "defined" | "alreadyExists" | "rejected" | "stationNotFound";
  lineUri?: string;
  did?: string;
  lineId?: string;
  error?: string;
}

export interface GetLineInput {
  lineId: string;
}

export interface GetLineOutput {
  line?: LineView;
  error?: string;
}

export interface ListLinesInput {
  operator?: string;
  limit?: number;
  cursor?: string;
}

export interface ListLinesOutput {
  items: LineView[];
  cursor?: string;
  total: number;
}

// ─── Train run ──────────────────────────────────────────────────────

export type RunStatus =
  | "scheduled"
  | "running"
  | "delayed"
  | "completed"
  | "cancelled";

export interface TrainRunRecord {
  did: string;
  runId: string;
  lineId: string;
  originStationId?: string;
  destStationId?: string;
  /** YYYY-MM-DD service day. */
  serviceDay?: string;
  status: RunStatus;
  createdAt: string;
}

export interface TrainRunView extends TrainRunRecord {
  runUri: string;
}

export interface ScheduleTrainInput {
  runId: string;
  lineId: string;
  originStationId?: string;
  destStationId?: string;
  serviceDay?: string;
}

export interface ScheduleTrainOutput {
  status: "scheduled" | "alreadyExists" | "rejected" | "lineNotFound";
  runUri?: string;
  did?: string;
  runId?: string;
  error?: string;
}

export interface RecordRunStatusInput {
  runId: string;
  status: Exclude<RunStatus, "scheduled">;
}

export interface RecordRunStatusOutput {
  status: "updated" | "notFound" | "rejected";
  runId?: string;
  newStatus?: RunStatus;
  error?: string;
}

export interface GetRunInput {
  runId: string;
}

export interface GetRunOutput {
  run?: TrainRunView;
  error?: string;
}

export interface ListTrainRunsInput {
  lineId?: string;
  serviceDay?: string;
  status?: RunStatus;
  limit?: number;
  cursor?: string;
}

export interface ListTrainRunsOutput {
  items: TrainRunView[];
  cursor?: string;
  total: number;
}

export interface CoverageInput {
  maxScan?: number;
}

export interface CoverageOutput {
  stationCount?: number;
  lineCount?: number;
  runCount?: number;
  runsByStatus?: Record<string, number>;
  truncated?: boolean;
  error?: string;
}

// ─── Helpers ────────────────────────────────────────────────────────

export const RUN_STATUSES: ReadonlySet<RunStatus> = new Set([
  "scheduled",
  "running",
  "delayed",
  "completed",
  "cancelled",
]);

export function stationDid(id: string): string {
  return `${ORAIL_DID_PREFIX}station:${id.toLowerCase()}`;
}
export function stationRkey(id: string): string {
  return `station-${id.toLowerCase()}`;
}
export function lineDid(id: string): string {
  return `${ORAIL_DID_PREFIX}line:${id.toLowerCase()}`;
}
export function lineRkey(id: string): string {
  return `line-${id.toLowerCase()}`;
}
export function runDid(id: string): string {
  return `${ORAIL_DID_PREFIX}run:${id.toLowerCase()}`;
}
export function runRkey(id: string): string {
  return `run-${id.toLowerCase()}`;
}
