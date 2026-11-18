import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test('B1-JSF-01 · invalid fields retain input; corrected registration is visible in the same rendered page', async ({ page }) => {
  await page.goto('index.jsf');
  await expect(page.getByRole('heading', { name: 'Member Registration' })).toBeVisible();
  const name = page.getByLabel('Name:', { exact: true });
  const email = page.getByLabel('Email:', { exact: true });
  const phone = page.getByLabel('Phone #:', { exact: true });
  const submit = page.getByRole('button', { name: 'Register', exact: true });
  await name.fill('Nom1');
  await email.fill('invalid');
  await phone.fill('1');
  await submit.click();
  await expect(page.getByText('Must not contain numbers', { exact: true })).toBeVisible();
  await expect(page.locator('form .invalid')).toHaveCount(3);
  await expect(name).toHaveValue('Nom1');
  await expect(email).toHaveValue('invalid');
  await expect(phone).toHaveValue('1');

  const address = randomUUID() + '@example.test';
  await name.fill('Élodie أمينة');
  await email.fill(address);
  await phone.fill('0123456789');
  await submit.click();
  await expect(page.getByText('Registered!', { exact: true })).toBeVisible();
  const row = page.locator('table.simpletablestyle tbody tr').filter({ has: page.getByRole('cell', { name: address, exact: true }) });
  await expect(row).toHaveCount(1);
  await expect(row.getByText('Élodie أمينة', { exact: true })).toBeVisible();
  await expect(row.getByText('0123456789', { exact: true })).toBeVisible();

  // A fresh GET avoids the browser resubmitting the form that created the member.
  await page.goto('index.jsf');
  await expect(row).toHaveCount(1);
  await row.getByRole('link').click();
  await expect(page).toHaveURL(/\/rest\/members\/\d+$/);
  const id = Number(new URL(page.url()).pathname.split('/').pop());
  expect(JSON.parse(await page.locator('body').innerText())).toEqual({ id, name: 'Élodie أمينة', email: address, phoneNumber: '0123456789' });

  await page.goto('index.jsf');
  await name.fill('Autre Nom');
  await email.fill(address);
  await phone.fill('0123456789');
  await submit.click();
  await expect(page.locator('form .invalid').first()).toBeVisible();
  await expect(row).toHaveCount(1);
  await expect(row.getByText('Élodie أمينة', { exact: true })).toBeVisible();
});
