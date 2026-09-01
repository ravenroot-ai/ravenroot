import { constants } from "node:fs";
import { access } from "node:fs/promises";

import puppeteer from "puppeteer";

const executables = [
  await puppeteer.executablePath({ headless: false }),
  await puppeteer.executablePath({ headless: "shell" }),
];

for (const executable of executables) {
  await access(executable, constants.X_OK);
  process.stdout.write(`${executable}\n`);
}
