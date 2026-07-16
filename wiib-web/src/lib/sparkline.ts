/** 迷你走势线坐标：折线归一化到 100x28 视口；range=0（横盘）时画中线。 */
export function sparkPoints(data: number[]): string {
  const min = Math.min(...data), max = Math.max(...data);
  const range = max - min || 1;
  const W = 100, H = 28, PAD = 2;
  return data
    .map((v, i) => {
      const x = (i / (data.length - 1)) * W;
      const y = PAD + (1 - (v - min) / range) * (H - PAD * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}
