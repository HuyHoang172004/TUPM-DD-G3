import { useEffect, useRef } from 'react';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { Metric, Summary } from './types';
import { number } from './format';
echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

export function Chart({ metric, summary }: { metric: Metric; summary?: Summary }) {
  const node = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!node.current) return;
    const chart = echarts.init(node.current);
    chart.setOption({
      animation: false, textStyle: { fontFamily: 'Segoe UI, sans-serif' },
      grid: { left: 70, top: 28, right: 25, bottom: 35 },
      xAxis: { type: 'category', data: ['A · Đối chứng', 'B · Tối ưu'], axisTick: { show: false }, axisLine: { lineStyle: { color: '#dce3ee' } }, axisLabel: { color: '#53617a', fontSize: 14 } },
      yAxis: { type: 'value', name: metric.unit, nameTextStyle: { color: '#6b7890' }, splitLine: { lineStyle: { color: '#edf0f6', type: 'dashed' } }, axisLabel: { color: '#6b7890' } },
      tooltip: { trigger: 'axis', formatter: (params: unknown) => {
        const rows = params as { dataIndex: number }[];
        const arm = rows[0]?.dataIndex === 0 ? 'A' : 'B';
        const stats = summary?.[arm];
        return `${arm} · median ${number(stats?.median, metric.unit)}<br/>IQR ${number(stats?.q1, metric.unit)} – ${number(stats?.q3, metric.unit)}<br/>n = ${stats?.n ?? 0}`;
      } },
      series: [{ type: 'bar', barWidth: 68, data: [
        { value: summary?.A.median ?? null, itemStyle: { color: '#a6b6d1', borderRadius: [8, 8, 0, 0] } },
        { value: summary?.B.median ?? null, itemStyle: { color: '#4f55df', borderRadius: [8, 8, 0, 0] } },
      ], label: { show: true, position: 'top', color: '#24334c', formatter: (p: { value: unknown }) => number(typeof p.value === 'number' ? p.value : null, metric.unit) } }],
    });
    const resize = new ResizeObserver(() => chart.resize());
    resize.observe(node.current);
    return () => { resize.disconnect(); chart.dispose(); };
  }, [metric, summary]);
  return <div ref={node} className="chart" role="img" aria-label={`So sánh ${metric.label} của A và B; dữ liệu median và IQR có trong thẻ chỉ số.`} />;
}
