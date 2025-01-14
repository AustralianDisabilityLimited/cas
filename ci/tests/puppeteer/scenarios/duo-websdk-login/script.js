const puppeteer = require('puppeteer');
const assert = require('assert');
const cas = require('../../cas.js');

(async () => {
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);
    await page.goto("https://localhost:8443/cas/login?authn_method=mfa-duo");
    await cas.loginWith(page, "casuser", "Mellon");
    await cas.loginDuoSecurityBypassCode(page, "websdk");
    console.log(await page.url())
    await cas.assertInnerText(page, '#content div h2', "Log In Successful");
    await cas.assertTicketGrantingCookie(page);

    const endpoints = ["duoPing", "duoAccountStatus/casuser", "duoAdmin/casuser?providerId=mfa-duo"];
    const baseUrl = "https://localhost:8443/cas/actuator/"
    for (let i = 0; i < endpoints.length; i++) {
        let url = baseUrl + endpoints[i];
        const response = await page.goto(url);
        console.log(`${response.status()} ${response.statusText()}`)
        assert(response.ok())
    }
    await browser.close();
})();
