import { useEffect, useRef } from "react";
import {
  init,
  use as registerCharts,
  type EChartsCoreOption,
} from "echarts/core";
import { BarChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
} from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
registerCharts([
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  SVGRenderer,
]);
export function Chart({
  option,
  label,
}: {
  option: EChartsCoreOption;
  label: string;
}) {
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!host.current) return;
    const chart = init(host.current, undefined, { renderer: "svg" });
    chart.setOption(option);
    const resize = () => chart.resize();
    const observer = new ResizeObserver(resize);
    observer.observe(host.current);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [option]);
  return <div ref={host} className="chart" role="img" aria-label={label} />;
}
