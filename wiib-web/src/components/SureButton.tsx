import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '../lib/utils';

/**
 * 再点一次才生效的按钮（删除、整段替换这类撤不回的）：第一下换成确认的说法、变红，3 秒没点第二下自己还原。
 * className 一直挂着（如 btn sm），idleClassName 只在还没点第一下时挂（如 plain，变红时要摘掉）；
 * onArm 是点第一下时要多说的话（比如删了会影响谁），调用方自己弹
 */
export function SureButton({ onConfirm, onArm, sureLabel, children, className, idleClassName, sureClassName = 'danger', disabled, title, ariaLabel }: {
  onConfirm: () => void;
  onArm?: () => void;
  /** 等第二下时按钮上的字（只有图标的按钮可以还是那个图标，靠 sureClassName 变红） */
  sureLabel: ReactNode;
  children: ReactNode;
  className?: string;
  idleClassName?: string;
  sureClassName?: string;
  disabled?: boolean;
  title?: string;
  ariaLabel?: string;
}) {
  const [sure, setSure] = useState(false);
  const timer = useRef(0);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const click = () => {
    window.clearTimeout(timer.current);
    if (sure) {
      setSure(false);
      onConfirm();
      return;
    }
    setSure(true);
    onArm?.();
    timer.current = window.setTimeout(() => setSure(false), 3000);
  };
  return (
    <button type="button" className={cn(className, sure ? sureClassName : idleClassName)} disabled={disabled} title={title}
            aria-label={ariaLabel} onClick={click}>
      {sure ? sureLabel : children}
    </button>
  );
}
