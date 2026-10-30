import type { ReactNode } from 'react';
import { IS_LITE } from '@/lib/edition';

/**
 * A section header with title and optional description, separated by a border.
 */
export function SettingSection({
  title,
  description,
  children,
}: {
  title?: string;
  description?: string;
  children: ReactNode;
}) {
  // Lite: a small label over a card of rows (grouped list), not a heading.
  if (IS_LITE) {
    return (
      <section className="mt-7">
        {title && (
          <h3 className="mb-2 ml-1 font-mono text-[11px] font-medium uppercase tracking-[0.08em] text-muted-foreground">
            {title}
          </h3>
        )}
        {description && <p className="mb-3 ml-1 text-sm text-muted-foreground">{description}</p>}
        <div className="divide-y divide-border rounded-xl border border-border bg-card">
          {children}
        </div>
      </section>
    );
  }
  return (
    <div className="space-y-1">
      {title && <h3 className="text-lg font-semibold">{title}</h3>}
      {description && <p className="text-sm text-muted-foreground">{description}</p>}
      <div className={`${title || description ? 'pt-3' : ''} space-y-0 divide-y divide-border/60`}>
        {children}
      </div>
    </div>
  );
}

/**
 * A single settings row: label+description on the left, action on the right.
 * Use for toggles, inputs, buttons, badges — any control type.
 */
export function SettingRow({
  title,
  description,
  htmlFor,
  action,
  children,
}: {
  title: string;
  description?: string;
  htmlFor?: string;
  /** Right-aligned control (checkbox, button, badge, etc.) */
  action?: ReactNode;
  /** Full-width content rendered below the label row (for sliders, inputs, etc.) */
  children?: ReactNode;
}) {
  return (
    <div className={IS_LITE ? 'px-4 py-3.5' : 'py-3'}>
      <div className="flex items-center justify-between gap-8">
        <div className="min-w-0">
          <label
            htmlFor={htmlFor}
            className={`text-sm font-medium leading-none select-none ${htmlFor ? 'cursor-pointer' : ''}`}
          >
            {title}
          </label>
          {description && (
            <p
              className={
                IS_LITE
                  ? 'mt-1 text-[13px] text-muted-foreground'
                  : 'text-sm text-muted-foreground mt-0.5'
              }
            >
              {description}
            </p>
          )}
        </div>
        {action && <div className="shrink-0">{action}</div>}
      </div>
      {children && <div className="mt-3">{children}</div>}
    </div>
  );
}
