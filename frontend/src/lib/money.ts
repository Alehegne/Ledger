import Decimal from 'decimal.js';

Decimal.set({ precision: 40, rounding: Decimal.ROUND_HALF_UP });

export const decimal = (value: Decimal.Value) => new Decimal(value ?? 0);

export const toMoneyString = (value: Decimal.Value) => decimal(value).toFixed(4);

export const formatMoney = (value: Decimal.Value, currency = 'USD') => {
  const amount = decimal(value).toDecimalPlaces(2).toNumber();
  const safeCurrency =
    typeof currency === 'string' && /^[A-Za-z]{3}$/.test(currency.trim())
      ? currency.trim().toUpperCase()
      : 'USD';
  try {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: safeCurrency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(amount);
  } catch {
    return `${safeCurrency} ${amount.toFixed(2)}`;
  }
};

export const sumBySide = (
  lines: Array<{ side: 'DEBIT' | 'CREDIT'; amount: string | number }>,
  side: 'DEBIT' | 'CREDIT',
) => lines.filter((line) => line.side === side).reduce((acc, line) => acc.plus(decimal(line.amount)), decimal(0));
