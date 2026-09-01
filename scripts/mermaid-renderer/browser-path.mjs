import puppeteer from "puppeteer";

process.stdout.write(await puppeteer.executablePath());
