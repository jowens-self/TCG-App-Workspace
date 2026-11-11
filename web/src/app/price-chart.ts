import { CurrencyPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { ChartDatum, PricePhase } from './api.models';
import { phaseRuns } from './launch-pricing';

interface ChartPoint {
  x: number;
  y: number;
  date: string;
  phase: PricePhase | null;
}

/** A shaded stretch behind the line, in SVG units. */
interface Band {
  x: number;
  w: number;
  phase: PricePhase;
}

/**
 * Hand-rolled SVG line chart for one variant's price history.
 *
 * No charting library on purpose: this needs one line and two labels, and
 * a dependency here would be a decision the project doesn't need to make
 * yet. If charts get richer (multiple series, brushing, tooltips on a
 * touch device), that's the point to reconsider, not before.
 *
 * Takes a plain {date, value} series rather than PricePoint, so it knows
 * nothing about where the numbers came from. Points with a null value are
 * dropped rather than plotted as zero: a missing observation is not a value
 * of $0, and connecting across the gap is the better reading.
 *
 * A point may carry a launch phase (pre-release, or early pricing while a new
 * card settles). Those stretches are drawn over a shaded band, so when a
 * caller does include them they still read as launch pricing, not as trend.
 */
@Component({
  selector: 'price-chart',
  imports: [CurrencyPipe],
  template: `
    @if (points().length < 2) {
      <p class="chart-empty">Not enough history to chart.</p>
    } @else {
      <figure class="chart">
        <svg
          [attr.viewBox]="'0 0 ' + W + ' ' + H"
          preserveAspectRatio="none"
          role="img"
          [attr.aria-label]="
            'Price history from ' + first().date + ' to ' + last().date
          "
        >
          <!-- The high of what is DRAWN. Deliberately not called the
               all-time high: unticked launch pricing is left off the chart,
               so its peak can sit below the real one, which is on the
               variant button above the chart. -->
          @for (b of bands(); track b.x) {
            <rect
              class="band"
              [class.pre]="b.phase === 'pre_release'"
              [attr.x]="b.x"
              [attr.width]="b.w"
              y="0"
              [attr.height]="H"
            />
          }
          <line
            class="peak-line"
            [attr.x1]="0"
            [attr.x2]="W"
            [attr.y1]="peakY()"
            [attr.y2]="peakY()"
          />
          <path class="area" [attr.d]="areaPath()" />
          <path class="line" [attr.d]="linePath()" />
          <circle class="dot" [attr.cx]="last().x" [attr.cy]="last().y" r="4" />
        </svg>
        <figcaption>
          <span class="lo">low {{ min() | currency }}</span>
          <span class="range">{{ first().date }} → {{ last().date }}</span>
          <span class="hi">high {{ max() | currency }}</span>
        </figcaption>
        @if (bands().length) {
          <p class="legend">
            @if (hasPhase('pre_release')) {
              <span class="key"><i class="swatch pre"></i>pre-release</span>
            }
            @if (hasPhase('early')) {
              <span class="key"><i class="swatch"></i>early pricing</span>
            }
          </p>
        }
      </figure>
    }
  `,
  styles: `
    .chart { margin: 0; }
    svg {
      width: 100%;
      height: 160px;
      display: block;
      overflow: visible;
    }
    .line {
      fill: none;
      stroke: var(--accent);
      stroke-width: 2;
      vector-effect: non-scaling-stroke;
      stroke-linejoin: round;
    }
    .area { fill: var(--accent-fade); stroke: none; }
    .dot { fill: var(--accent); }
    /* Launch pricing: a tint behind the line, darker before release. */
    .band { fill: var(--warn); opacity: 0.08; }
    .band.pre { opacity: 0.16; }
    .legend {
      display: flex;
      gap: 0.9rem;
      margin: 0.35rem 0 0;
      font-size: 0.75rem;
      color: var(--muted);
    }
    .key { display: inline-flex; align-items: center; gap: 0.3rem; }
    .swatch {
      width: 0.7rem;
      height: 0.7rem;
      border-radius: 2px;
      background: var(--warn);
      opacity: 0.25;
    }
    .swatch.pre { opacity: 0.45; }
    .peak-line {
      stroke: var(--line);
      stroke-width: 1;
      stroke-dasharray: 3 3;
      vector-effect: non-scaling-stroke;
    }
    figcaption {
      display: flex;
      justify-content: space-between;
      font-size: 0.75rem;
      color: var(--muted);
      margin-top: 0.4rem;
      font-variant-numeric: tabular-nums;
    }
    /* Lives here, not in search.css: Angular scopes styles per component,
       so a rule for this element written in the parent never applies. */
    .chart-empty {
      color: var(--muted);
      font-size: 0.85rem;
      margin: 0;
    }
  `,
})
export class PriceChart {
  readonly series = input.required<readonly ChartDatum[]>();

  protected readonly W = 600;
  protected readonly H = 160;

  /** Points with no value at all are dropped, not zeroed. */
  private readonly usable = computed(() =>
    this.series().filter((p) => p.value != null),
  );

  protected readonly min = computed(() =>
    Math.min(...this.usable().map((p) => p.value!)),
  );
  protected readonly max = computed(() =>
    Math.max(...this.usable().map((p) => p.value!)),
  );

  private y(value: number): number {
    const lo = this.min();
    const hi = this.max();
    const pad = 8;
    // A dead-flat series would divide by zero; render it mid-height.
    return hi === lo ? this.H / 2 : this.H - pad - ((value - lo) / (hi - lo)) * (this.H - pad * 2);
  }

  protected readonly points = computed<ChartPoint[]>(() => {
    const pts = this.usable();
    if (pts.length < 2) return [];
    return pts.map((p, i) => ({
      x: (i / (pts.length - 1)) * this.W,
      y: this.y(p.value!),
      date: p.date,
      phase: p.phase ?? null,
    }));
  });

  /**
   * One shaded rect per run of launch-phase points, reaching half a step
   * either side of its first and last point so neighbouring runs meet.
   */
  protected readonly bands = computed<Band[]>(() => {
    const pts = this.points();
    if (pts.length < 2) return [];
    const half = this.W / (pts.length - 1) / 2;
    return phaseRuns(pts.map((p) => p.phase)).map((r) => {
      const x = Math.max(0, pts[r.from].x - half);
      const end = Math.min(this.W, pts[r.to].x + half);
      return { x, w: end - x, phase: r.phase };
    });
  });

  protected hasPhase(phase: PricePhase): boolean {
    return this.bands().some((b) => b.phase === phase);
  }

  protected readonly first = computed(() => this.points()[0]);
  protected readonly last = computed(() => this.points()[this.points().length - 1]);

  /** Smallest y is the highest price, since SVG's origin is the top left. */
  protected readonly peakY = computed(() => {
    const pts = this.points();
    return pts.length ? Math.min(...pts.map((p) => p.y)) : 0;
  });

  protected readonly linePath = computed(() =>
    this.points()
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)},${p.y.toFixed(2)}`)
      .join(' '),
  );

  protected readonly areaPath = computed(() => {
    const pts = this.points();
    if (!pts.length) return '';
    return `${this.linePath()} L${this.W},${this.H} L0,${this.H} Z`;
  });
}
