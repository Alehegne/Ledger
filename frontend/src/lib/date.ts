export const formatDate = (isoDate: string) => {
  const d = new Date(isoDate);
  return new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' }).format(d);
};

export const formatDateOnly = (isoDate: string) => {
  const d = new Date(`${isoDate}T00:00:00`);
  return new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' }).format(d);
};

export const todayISO = () => new Date().toISOString().slice(0, 10);
