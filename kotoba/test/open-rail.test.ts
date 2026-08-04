import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  defineStation,
  getStation,
  listStations,
  defineLine,
  getLine,
  listLines,
  scheduleTrain,
  recordRunStatus,
  getRun,
  listTrainRuns,
  coverage,
} from "../src/index.js";

describe("open-rail kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:open-rail.etzhayyim.com" });
  });

  describe("station + line", () => {
    beforeEach(async () => {
      await defineStation(e, { stationId: "ST-A", name: "Aoyama" });
      await defineStation(e, { stationId: "ST-B", name: "Bashi" });
    });
    it("defines stations + a line over an existing sequence", async () => {
      expect((await getStation(e, { stationId: "ST-A" })).station?.name).toBe("Aoyama");
      expect((await listStations(e)).total).toBe(2);
      const r = await defineLine(e, { lineId: "L-1", name: "Green Line", operator: "JR", stations: [{ stationId: "ST-A", kmPostM: 0 }, { stationId: "ST-B", kmPostM: 4200, dwellSec: 30 }] });
      expect(r.status).toBe("defined");
      expect((await getLine(e, { lineId: "L-1" })).line?.stations.length).toBe(2);
      expect((await listLines(e, { operator: "JR" })).total).toBe(1);
    });
    it("rejects <2 stations + missing station in sequence", async () => {
      expect((await defineLine(e, { lineId: "L-X", name: "x", stations: [{ stationId: "ST-A" }] })).status).toBe("rejected");
      expect((await defineLine(e, { lineId: "L-Y", name: "x", stations: [{ stationId: "ST-A" }, { stationId: "GHOST" }] })).status).toBe("stationNotFound");
    });
  });

  describe("train run + coverage", () => {
    beforeEach(async () => {
      await defineStation(e, { stationId: "ST-A", name: "A" });
      await defineStation(e, { stationId: "ST-B", name: "B" });
      await defineLine(e, { lineId: "L-1", name: "Green", stations: [{ stationId: "ST-A" }, { stationId: "ST-B" }] });
    });
    it("schedules against existing line; rejects missing line", async () => {
      expect((await scheduleTrain(e, { runId: "R-1", lineId: "L-1", originStationId: "ST-A", destStationId: "ST-B", serviceDay: "2026-07-01" })).status).toBe("scheduled");
      expect((await getRun(e, { runId: "R-1" })).run?.status).toBe("scheduled");
      expect((await scheduleTrain(e, { runId: "R-2", lineId: "NOPE" })).status).toBe("lineNotFound");
    });
    it("advances status + terminal guard", async () => {
      await scheduleTrain(e, { runId: "R-1", lineId: "L-1" });
      expect((await recordRunStatus(e, { runId: "R-1", status: "running" })).newStatus).toBe("running");
      expect((await recordRunStatus(e, { runId: "R-1", status: "completed" })).newStatus).toBe("completed");
      expect((await recordRunStatus(e, { runId: "R-1", status: "delayed" })).status).toBe("rejected");
    });
    it("lists by line/status + coverage rolls up", async () => {
      await scheduleTrain(e, { runId: "R-1", lineId: "L-1", serviceDay: "2026-07-01" });
      expect((await listTrainRuns(e, { lineId: "L-1", status: "scheduled" })).total).toBe(1);
      const cov = await coverage(e);
      expect(cov.stationCount).toBe(2);
      expect(cov.lineCount).toBe(1);
      expect(cov.runCount).toBe(1);
      expect(cov.runsByStatus?.scheduled).toBe(1);
    });
  });
});
