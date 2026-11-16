import React from 'react';

interface ErrorBoundaryState {
  error: Error | null;
}

// حاجز أخطاء React: بدونه أي خطأ غير متوقع أثناء الرسم يحوّل شاشة التلفزيون
// بالكامل لسوداء خالية من أي رسالة، وبدون console يصل له المستخدم على جهازه
export class ErrorBoundary extends React.Component<React.PropsWithChildren, ErrorBoundaryState> {
  constructor(props: React.PropsWithChildren) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('Uncaught render error:', error, info.componentStack);
  }

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.error) {
      return (
        <div className="w-full h-full flex flex-col items-center justify-center bg-[#0B0B0D] text-white p-12 text-center select-none">
          <div className="text-6xl mb-6">⚠️</div>
          <h2 className="text-3xl font-bold mb-4">حدث خطأ غير متوقع</h2>
          <p className="text-[#A1A1A6] text-xl mb-8 max-w-lg">
            واجه التطبيق مشكلة فنية. أعد التشغيل وسيتم حل الأمر غالباً تلقائياً.
          </p>
          <button
            tabIndex={0}
            autoFocus
            onClick={this.handleReload}
            className="tv-focusable bg-[#FFFFFF] text-[#0B0B0D] font-bold text-xl px-10 py-4 rounded-2xl shadow-xl focus:ring-4 focus:ring-white"
          >
            إعادة تشغيل التطبيق
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
